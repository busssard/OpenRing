package app.openring.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import app.openring.core.Texts;
import app.openring.core.analysis.Scores;
import app.openring.data.Json;
import app.openring.ui.Pages.Nav;

/** The Today tab (issue #293, phase 0: moved out of {@link Pages} unchanged). */
final class TodayPage {
    private TodayPage() {}

    /**
     * Today (owner's wish of 17 November: "The sentence is the main input for the human"): the scores with the battery in one
     * folded line per device, then Your day with its sentence and line, so both are on the first screen with a ring, a watch
     * or only the phone; the rest below, in the order {@code core/TodayCards} gives.
     */
    static View view(final Context c, DayData d, final Nav nav, String syncLine) {
        LinearLayout root = Ui.vbox(c);
        // without a ring by choice (issue #329) the ring's buttons add one instead
        final boolean noRing = PhoneViews.noRing(c);
        View left = d.has.ring ? PageParts.leftOutCard(c, d) : null;
        for (String k : app.openring.core.TodayCards.of(d.has, !d.badges.isEmpty(), left != null, d.periodShown, d.handAsk)) {
            int before = root.getChildCount();
            if (app.openring.core.TodayCards.SCORES.equals(k)) {
                root.addView(d.has.ring ? ringScores(c, d, nav, noRing) : DeviceViews.scoresWithout(c, d, nav));
            } else if (app.openring.core.TodayCards.DAY.equals(k)) {
                View day = d.has.ring ? ringDay(c, d, nav, noRing) : dayCard(c, d, d.has.watch && !Double.isNaN(d.score("readiness")), null);
                if (day != null) root.addView(day);
            } else if (app.openring.core.TodayCards.HAND_ASK.equals(k)) {
                // no night for last night after a sync (issue #427): enter it by hand, or Not now for this night
                root.addView(HandViews.askCard(c, d, nav));
            } else if (app.openring.core.TodayCards.LIFE.equals(k)) {
                // life factors (issue #292): one tap chips for the day shown, right after Your day
                root.addView(LifeCard.today(c, d, nav));
            } else if (app.openring.core.TodayCards.DEVICE_NOTE.equals(k)) {
                root.addView(DeviceViews.deviceNote(c, d));
            } else if (app.openring.core.TodayCards.LEFT_OUT.equals(k)) {
                // a time left out because someone else wore the ring (issue #254); a day left out as a whole shows only this
                root.addView(left);
            } else if (app.openring.core.TodayCards.FINISH_WATCH.equals(k)) {
                root.addView(DeviceViews.finishWatch(c));
            } else if (app.openring.core.TodayCards.BADGES.equals(k)) {
                // the badges of the last 7 days (issue #265), only when there is one (no empty card)
                root.addView(BadgeViews.row(c, d, nav));
            } else if (app.openring.core.TodayCards.RING_METRICS.equals(k)) {
                if (!d.emptyAll()) ringMetrics(c, d, root);
            } else if (app.openring.core.TodayCards.LIVE.equals(k)) {
                root.addView(liveCard(c, nav, noRing));
            } else if (app.openring.core.TodayCards.WATCH_DAY.equals(k)) {
                root.addView(DeviceViews.watchDay(c, d));
            } else if (app.openring.core.TodayCards.PHONE_DAY.equals(k)) {
                root.addView(DeviceViews.phoneDay(c, d));
            } else if (app.openring.core.TodayCards.PERIOD.equals(k)) {
                // the period tracker near the bottom (issue #252): a tap opens Body, the buttons log the day shown
                root.addView(PeriodCard.today(c, d, nav));
            }
            // the tiles (owner's wish of 18 November): Today's own keys, so a tile keeps its place whatever its caption says
            if (root.getChildCount() == before + 1) todayTile(root.getChildAt(before), k, d);
            // the parts the short tour points at (issue #453)
            if (root.getChildCount() == before + 1) TourOverlay.mark(root.getChildAt(before), tourKey(k));
        }
        TextView foot = Ui.text(c, syncLine, 12, Ui.TEXT_3, Ui.REGULAR);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 16));
        root.addView(foot);
        return root;
    }

    /** The short tour's part for a card of Today (issue #453): the scores and the life factors, null for the others. */
    static String tourKey(String k) {
        return app.openring.core.TodayCards.SCORES.equals(k) ? app.openring.core.Tour.SCORES : app.openring.core.TodayCards.LIFE.equals(k) ? app.openring.core.Tour.LIFE : null;
    }

    /** Names a tile of Today by its {@code core/TodayCards} key, with a title where the card has no caption of its own. */
    static void todayTile(View card, String k, DayData d) {
        String key = app.openring.core.Layout.key(k);
        if (app.openring.core.TodayCards.SCORES.equals(k)) {
            // folded: the four scores as small dials in a row (owner's wish of 21 November); the line when there is none
            Tiles.name(card, key, "Scores", scoresLine(d));
            Tiles.dials(card, todayScores(d), new int[] {Ui.READY, Ui.SLEEP, Ui.ACTIVE, Ui.OXY}, SCORE_NAMES);
            if (Tiles.spec(card).dials != null) Tiles.summary(card, "");
        }
        else if (app.openring.core.TodayCards.DEVICE_NOTE.equals(k)) Tiles.name(card, key, "About these scores", null);
        else if (app.openring.core.TodayCards.FINISH_WATCH.equals(k)) Tiles.name(card, key, "Your watch", app.openring.core.Start.FINISH_TITLE);
        else if (app.openring.core.TodayCards.LIVE.equals(k)) Tiles.name(card, key, "Live heart rate", null);
        else Tiles.spec(card).key = key;
    }

    /** The folded Scores tile's line: "Readiness 94, sleep 89, activity 99, screen 87", the scores there are. */
    static String scoresLine(DayData d) {
        return app.openring.core.Layout.scoresLine(todayScores(d), SCORE_NAMES);
    }

    /** The names of Today's four scores, in the order of {@link #todayScores}. */
    static final String[] SCORE_NAMES = {"Readiness", "Sleep", "Activity", "Screen"};

    /** Today's four scores (readiness, sleep, activity, screen), NaN where one is missing. */
    static double[] todayScores(DayData d) {
        return new double[] {d.score("readiness"), d.score("sleep"), d.score("activity_score"), d.score("screen")};
    }

    /** The ring's four score dials, the battery in one folded line per device under them, and Find my ring as a small button. */
    static View ringScores(final Context c, DayData d, Nav nav, final boolean noRing) {
        LinearLayout rings = Ui.card(c);
        LinearLayout row = Ui.hbox(c);
        row.setGravity(Gravity.CENTER);
        row.addView(ringButton(c, d.score("readiness"), "Readiness", Ui.READY, nav, Pages.READINESS), Ui.weight(1));
        row.addView(ringButton(c, d.score("sleep"), "Sleep", Ui.SLEEP, nav, Pages.SLEEP), Ui.weight(1));
        row.addView(ringButton(c, d.score("activity_score"), "Activity", Ui.ACTIVE, nav, Pages.ACTIVITY), Ui.weight(1));
        double sc = d.score("screen");
        row.addView(ringButton(c, sc, "Screen", Ui.OXY, nav, Pages.SCREEN, Double.isNaN(sc) && !ScreenPage.screenReady(c) ? "Tap to set up" : null), Ui.weight(1));
        rings.addView(row);
        // the battery of each device right under the scores (owner's wish of 15 November), one line each (17 November)
        View bat = BatteryViews.rows(c, d);
        if (bat != null) rings.addView(bat);
        // issue #74; a small text button, so the day's line stays on the first screen (owner's wish of 17 November)
        TextView find = Ui.textButton(c, noRing ? app.openring.core.KeyState.ADD_RING : "Find my ring", Ui.OXY);
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (noRing) PhoneViews.addRing(c);
                else c.startActivity(new android.content.Intent(c, FinderActivity.class));
            }
        });
        if (bat == null) rings.addView(Ui.gap(c, 6));
        rings.addView(find, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return rings;
    }

    /**
     * Your day with a ring: the day card; a card that says what to do when the day has no data yet; nothing when the whole day is
     * left out (the Left out card says why).
     */
    static View ringDay(final Context c, DayData d, final Nav nav, boolean noRing) {
        if (d.emptyAll() && d.allLeftOut()) return null;
        if (d.emptyAll() && noRing) {
            // without a ring (issue #329): the phone's steps come with its readings, about every hour
            LinearLayout e = Ui.card(c);
            e.addView(Ui.h2(c, "No data for this day yet"));
            e.addView(Ui.gap(c, 6));
            e.addView(Ui.body(c, "Steps from this phone show on Activity after its first reading, screen time on Screen. "
                    + "Sleep and readiness need a ring or a watch."));
            e.addView(Ui.gap(c, 12));
            e.addView(PhoneViews.addRingButton(c, true), Ui.matchWrap());
            return e;
        }
        if (d.emptyAll()) {
            LinearLayout e = Ui.card(c);
            e.addView(Ui.h2(c, "No data for this day yet"));
            e.addView(Ui.gap(c, 6));
            e.addView(Ui.body(c, "Wear your ring and sync. Sleep appears after your first night, readiness after the morning sync, "
                    + "and baselines become personal after about two weeks. If you synced and still see nothing, the data check "
                    + "shows where it stops."));
            e.addView(Ui.gap(c, 12));
            TextView check = Ui.button(c, "Open data check", Ui.TEXT_2, false);
            check.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    c.startActivity(new android.content.Intent(c, LogActivity.class));
                }
            });
            e.addView(check, Ui.matchWrap());
            e.addView(Ui.gap(c, 12));
            TextView b = Ui.button(c, "Sync now", Ui.SLEEP, true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    nav.sync();
                }
            });
            e.addView(b, Ui.matchWrap());
            return e;
        }
        // the day's facts and a line that changes every day (owner's wish of 16 November); without a ring (issue #329) the
        // phone's steps, with the note that sleep and readiness need a ring or a watch
        return dayCard(c, d, !noRing, noRing ? "Sleep and readiness need a ring or a watch. Everything is worked out on this phone, nothing left the device."
                : null);
    }

    /** The ring's Key metrics and, when there is one, the symptom radar. */
    static void ringMetrics(Context c, DayData d, LinearLayout root) {
        LinearLayout stats = Ui.card(c);
        stats.addView(Ui.caption(c, "Key metrics"));
        JSONObject n = d.night;
        // the main device's night, else the other one's (issue #251); a watch night is Withings' own
        app.openring.core.withings.WatchDays.Night wn = d.merged == null ? null : d.merged.watchNight;
        stats.addView(Ui.statRow(c,
                wn != null ? Ui.stat(c, "Total sleep", Ui.hm(wn.asleepMin()), "", Ui.SLEEP, d.merged.main == app.openring.core.MainDevice.WATCH
                        ? "Time asleep last night, as your watch counted it" : "Last night from your watch, your ring has none")
                : Ui.stat(c, "Total sleep", n == null ? "--" : Ui.hm(Json.d(n, "asleep_min")), "", Ui.SLEEP,
                        d.handShown ? "Last night, entered by hand" : "Time asleep last night, naps not counted"),
                Ui.stat(c, "Resting HR", Ui.fmt(Json.d(n, "lowest_hr"), 0), "bpm", Ui.HEART, "Lowest heart rate while asleep")));
        stats.addView(Ui.statRow(c,
                Ui.stat(c, "HRV", Ui.fmt(Json.d(n, "avg_hrv"), 0), "ms", Ui.READY, "Average while asleep, higher is calmer"),
                // temp_dev is a difference from the usual skin temperature, not a body temperature (issue #32)
                Ui.stat(c, "Temp vs usual", PageParts.signed(Json.d(d.day, "temp_dev")), "°C", Ui.TEMP, "Skin temperature compared with your usual")));
        stats.addView(Ui.statRow(c,
                PageParts.stepsStat(c, d, Ui.ACTIVE, "Estimated from your movement"),
                Ui.stat(c, "Active burn", Ui.fmt(Json.d(d.day, "active_kcal"), 0), "kcal", Ui.ACTIVE, "Calories burned by moving")));
        String fill = PageParts.stepsLine(d);
        if (fill != null) stats.addView(Ui.text(c, fill, 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(stats);

        JSONObject radar = d.day == null ? null : d.day.optJSONObject("radar");
        if (radar != null && radar.optBoolean("available")) {
            LinearLayout r = Ui.card(c);
            r.addView(Ui.caption(c, "Symptom radar"));
            r.addView(Ui.gap(c, 4));
            int dec = radar.optInt("decision");
            r.addView(Ui.text(c, radar.optString("status"), 18, dec == 0 ? Ui.GOOD : Ui.WARN, Ui.REGULAR));
            r.addView(Ui.body(c, dec == 0 ? "Your temperature, heart rate, HRV and breathing look like your normal."
                    : "Some of your body signals moved away from your normal. Take it easy and see Body for details."));
            root.addView(r);
        }
    }

    /** Live heart rate: Start, or Add a ring without one. */
    static View liveCard(final Context c, final Nav nav, final boolean noRing) {
        LinearLayout live = Ui.card(c);
        LinearLayout lr = Ui.hbox(c);
        LinearLayout lt = Ui.vbox(c);
        lt.addView(Ui.h2(c, "Live heart rate"));
        lt.addView(Ui.body(c, noRing ? "Needs a ring" : "Measure right now with your ring"));
        lr.addView(lt, Ui.weight(1));
        TextView lb = Ui.button(c, noRing ? app.openring.core.KeyState.ADD_RING : "Start", Ui.HEART, false);
        lb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (noRing) PhoneViews.addRing(c);
                else nav.live(false);
            }
        });
        lr.addView(lb);
        live.addView(lr);
        return live;
    }

    static View ringButton(Context c, double v, String label, int color, final Nav nav, final int tab) {
        return ringButton(c, v, label, color, nav, tab, null);
    }

    /** A small score ring that opens its tab; four share a row, so each takes the width it gets (at most 96 dp). */
    static View ringButton(Context c, double v, String label, int color, final Nav nav, final int tab, String note) {
        Charts.ScoreRing r = new Charts.ScoreRing(c, v, label, color, false);
        LinearLayout box = Ui.vbox(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 84)));
        TextView verdict = Ui.text(c, note != null ? note : Double.isNaN(v) ? " " : Scores.verdict((int) v), 12, Ui.TEXT_2, Ui.REGULAR);
        verdict.setGravity(Gravity.CENTER);
        box.addView(verdict);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                nav.go(tab);
            }
        });
        return box;
    }

    /** The tag of the day's line on Today, for the first screen check of the screen test. */
    static final String DAY_LINE_TAG = "day_line";

    /** Whether "Why this line" is open on Today's Your day card; kept while the screen is rebuilt (extra {@code day_line_why}). */
    static final boolean[] dayWhyOpen = {false};

    /**
     * Today's "Your day" card (owner's wish of 16 November, {@code core/DayLine}): the sentence with the day's facts, the line
     * of the day, the readiness sentence ({@code withDigest}) or {@code note}, and "Why this line" with each check of the tree.
     * A tap on the card opens or closes it.
     */
    static View dayCard(Context c, DayData d, boolean withDigest, String note) {
        app.openring.core.DayLine.Result r = dayLine(c, d);
        // out of date: nothing below pretends to know the day
        String more = r.decision.branch == app.openring.core.DayLine.STALE ? null
                : withDigest ? digest(d, true, r.decision.branch == app.openring.core.DayLine.DEBT) : note;
        final LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Your day"));
        card.addView(Ui.gap(c, 6));
        card.addView(Ui.body(c, r.decision.evidence));
        card.addView(Ui.gap(c, 8));
        TextView line = Ui.text(c, r.line, 20, Ui.TEXT, Ui.LIGHT);
        // the screen test measures where the line ends on the first screen (MainActivity.firstScreen, demo mode only)
        line.setTag(DAY_LINE_TAG);
        card.addView(line);
        if (more != null && !more.isEmpty()) {
            card.addView(Ui.gap(c, 8));
            card.addView(Ui.text(c, more, 13, Ui.TEXT_2, Ui.REGULAR));
        }
        card.addView(Ui.gap(c, 6));
        List<String> why = new java.util.ArrayList<String>();
        why.add("Checked from the top, the first that fits picks the line:");
        why.addAll(r.why());
        why.add(Explain.DAY_LINE);
        final Fold how = Ui.howFold(c, dayWhyOpen, why, "Why this line");
        card.addView(how.view, Ui.matchWrap());
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                how.toggle();
            }
        });
        return card;
    }

    /**
     * The facts of the day for {@link #dayCard}: the ring's stored day, else the watch's or the phone's days, with the sync age,
     * the screen, a badge of the day and the period phase. The line is noted in the display memory only for today.
     */
    static app.openring.core.DayLine.Result dayLine(Context c, DayData d) {
        app.openring.data.Prefs p = app.openring.data.Prefs.of(c);
        java.time.LocalDate today = java.time.LocalDate.now();
        boolean isToday = d.date.equals(today);
        app.openring.core.DayLine.Facts f;
        if (d.has.ring && (d.day != null || d.night != null)) {
            f = app.openring.data.DayLines.ring(d.date, isToday, d.day, d.night, d.history);
        } else if (d.deviceHistory != null) {
            f = app.openring.core.DayLine.fromDevice(d.deviceHistory, d.date, isToday);
        } else {
            f = new app.openring.core.DayLine.Facts();
            f.date = d.date;
            f.isToday = isToday;
            f.device = d.has.ring ? app.openring.core.DayLine.RING : d.has.watch ? app.openring.core.DayLine.WATCH : app.openring.core.DayLine.PHONE;
            if (d.merged != null) f.steps = d.merged.steps;
        }
        // the app's step goal (the watch's setting, 10,000 unless changed) and the clock for "today after 15:00"
        if (f.device == app.openring.core.DayLine.RING) f.stepGoal = p.watchGoal();
        if (isToday) {
            java.time.LocalTime now = java.time.ZonedDateTime.now(d.zone).toLocalTime();
            f.hourNow = now.getHour() + now.getMinute() / 60.0;
        }
        // the demo never counts as out of date; a ring or a watch without a sync yet says so
        if (!p.demo() && f.device != app.openring.core.DayLine.PHONE) {
            long t = f.device == app.openring.core.DayLine.WATCH ? p.watchLastOk() : p.lastSync();
            f.neverSynced = t == 0;
            f.sinceSyncH = t == 0 ? Double.NaN : Math.max(0, System.currentTimeMillis() / 1000.0 - t) / 3600.0;
        }
        if (d.screen != null) {
            f.bedScreenMin = d.screen.bedMin;
            f.nightScreenMin = d.screen.nightMin;
            if (d.screen.day != null) f.screenMin = d.screen.day.totalMin;
        }
        for (app.openring.core.analysis.Badges.Badge b : d.badges) {
            if (b.date.equals(d.date)) {
                f.badge = app.openring.core.analysis.Badges.NAMES[b.kind];
                break;
            }
        }
        if (d.periodShown && d.periods != null) f.periodPhase = d.periods.phase(d.date);
        String stored = p.dayLineSeen();
        app.openring.core.DayLine.Memory m = app.openring.core.DayLine.Memory.parse(stored);
        app.openring.core.DayLine.Result r = app.openring.core.DayLine.of(f, m, isToday, Locale.getDefault());
        if (isToday && !m.toString().equals(stored)) p.setDayLineSeen(m.toString());
        return r;
    }

    /**
     * The readiness sentence and the sleep debt under the day's line (no filler when {@code forLine}), without the sleep debt
     * when the line said it ({@code noDebt}).
     */
    static String digest(DayData d, boolean forLine, boolean noDebt) {
        StringBuilder sb = new StringBuilder();
        JSONObject rd = d.scoreObj("readiness");
        if (rd != null) {
            JSONArray cs = rd.optJSONArray("contributors");
            JSONObject worst = null, best = null;
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject o = cs.optJSONObject(i);
                if (worst == null || o.optDouble("score") < worst.optDouble("score")) worst = o;
                if (best == null || o.optDouble("score") > best.optDouble("score")) best = o;
            }
            // the same ladder as the labels and colours on the Readiness page (issue #200)
            String line = best == null ? "" : Texts.contributorLine(best.optString("label"), worst.optString("label"),
                    worst.optDouble("score"), Locale.getDefault());
            sb.append(line.isEmpty() ? ". " : line + " ");
            if (rd.optBoolean("provisional")) sb.append("Your baselines are still forming, so scores will become more personal over the next two weeks. ");
        }
        JSONObject debt = d.day == null ? null : d.day.optJSONObject("sleep_debt");
        if (noDebt) {
            // the day's sentence above already gives the sleep debt
        } else if (debt != null && debt.optBoolean("valid")) {
            double m = debt.optDouble("debt_min", 0);
            if (m >= 180) sb.append("You carry about ").append(Ui.hm(m)).append(" of sleep debt. ");
        } else if (debt == null && HistoryViews.watch(d)) {
            // only the watch: its sleep debt by the ring's rule, the number of the Sleep tab (owner's report of 14 November)
            double[] w = d.deviceHistory.debt(d.date);
            if (w[1] > 0 && w[0] >= 180) sb.append("You carry about ").append(Ui.hm(w[0])).append(" of sleep debt. ");
        }
        // under the day's line the card has its own sentence, so no filler
        if (sb.length() == 0 && !forLine) sb.append("Synced and analysed on this phone. Nothing left the device.");
        return sb.toString().trim();
    }
}
