package app.openring.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import app.openring.core.Texts;
import app.openring.core.analysis.Scores;
import app.openring.core.analysis.StageShare;
import app.openring.core.analysis.TS;
import app.openring.core.analysis.WakeUps;
import app.openring.data.Json;

/** The parts several tabs share: the score card, contributors, info and chart cards, Left out, stage rows (issue #293, phase 0). */
final class PageParts {
    private PageParts() {}

    /**
     * The Steps tile (issue #251): the main device's steps with the other one's in the time the main one was not worn. With
     * only the ring, its own estimate and {@code ringNote}, as before.
     */
    static View stepsStat(Context c, DayData d, int color, String ringNote) {
        app.openring.core.MainDevice.Day m = d.merged;
        String note = m == null ? null : app.openring.core.MainDevice.stepsNote(m);
        if (note == null) return Ui.stat(c, "Steps", Ui.fmt(m == null ? Json.d(d.day, "steps") : m.steps, 0), "est.", color, ringNote);
        return Ui.stat(c, "Steps", Ui.fmt(m.steps, 0), note.startsWith("Counted") ? "" : "est.", color, note);
    }

    /** The line under the steps when part of them came from the other device, else null (issue #251). */
    static String stepsLine(DayData d) {
        return d.merged == null ? null : app.openring.core.MainDevice.stepsLine(d.merged, d.zone, Locale.getDefault());
    }

    /**
     * The card "Left out" on Today, Sleep and Activity (issue #254): each time left out on this day, how to bring it back
     * and what leaving out does. Null when nothing of the day is left out.
     */
    static View leftOutCard(Context c, DayData d) {
        List<double[]> l = d.leftOutToday();
        if (l.isEmpty()) return null;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "Left out"));
        for (double[] r : l) {
            card.addView(Ui.gap(c, 4));
            card.addView(Ui.body(c, app.openring.core.analysis.LeftOut.card(r[0], r[1], d.zone)));
        }
        card.addView(Ui.gap(c, 6));
        card.addView(Ui.text(c, Explain.LEFT_OUT, 12, Ui.TEXT_3, Ui.REGULAR));
        return card;
    }

    /**
     * Clock time rounded to the nearest minute, as every row of the timeline shows it; every night time on the
     * Sleep page uses it, so the same moment shows the same minute everywhere (issue #138).
     */
    static String minute(double unix, java.time.ZoneId z) {
        return WakeUps.clock(unix, z);
    }

    /** "+0.3", "-0.2", "0.0" near zero (not "-0.0"), with the phone's decimal sign (issues #32, #163). */
    static String signed(double v) {
        return Texts.signed(v, Locale.getDefault());
    }

    static View stageRow(Context c, String name, double min, double total, int color) {
        return stageRowPct(c, name, min, 100 * min / total, color);
    }

    /** A stage row with its percentage worked out by the caller (NaN shows "--"). */
    static View stageRowPct(Context c, String name, double min, double pct, int color) {
        LinearLayout row = Ui.hbox(c);
        row.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        View dot = new View(c);
        dot.setBackground(Ui.rounded(color, Ui.dp(c, 5)));
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10)));
        TextView t = Ui.text(c, "  " + name, 14, Ui.TEXT, Ui.REGULAR);
        row.addView(t, Ui.weight(1));
        row.addView(Ui.text(c, Ui.hm(min) + "   ", 14, Ui.TEXT, Ui.REGULAR));
        row.addView(Ui.text(c, Double.isNaN(pct) ? "--" : Ui.fmt(pct, 0) + "%", 13, Ui.TEXT_3, Ui.REGULAR));
        return row;
    }

    static View infoCard(Context c, String title, String text) {
        LinearLayout card = Ui.card(c);
        card.setBackground(Ui.rounded(Ui.CARD_HI, Ui.dp(c, 18)));
        card.addView(Ui.caption(c, title));
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.body(c, text));
        return card;
    }

    /** "07:42" for a moment, "--" when unknown. */
    static String clock(double unix, java.time.ZoneId zone) {
        if (Double.isNaN(unix)) return "--";
        java.time.ZonedDateTime z = java.time.Instant.ofEpochSecond((long) unix).atZone(zone == null ? java.time.ZoneId.systemDefault() : zone);
        return String.format(Locale.getDefault(), "%02d:%02d", z.getHour(), z.getMinute());
    }

    static TS daysSeries(DayData d, String field, int n) {
        return app.openring.data.DaySeries.of(d.end(n), d.chartSeries(field, n));
    }

    static View hero(Context c, double score, String label, int color, String sub) {
        return hero(c, score, label, color, sub, null);
    }

    /**
     * As above with a small line under it, null for none: where a score without a ring comes from, or the watch's version of a
     * ring's score (issue #379).
     */
    static View hero(Context c, double score, String label, int color, String sub, String note) {
        return hero(c, score, label, color, null, sub, note);
    }

    /**
     * As above with a big line under the verdict, null for none: the Sleep page's time asleep, "7h 12m asleep" (issue #409),
     * as big as the tiles on Today and in the score's colour.
     */
    static View hero(Context c, double score, String label, int color, String big, String sub, String note) {
        LinearLayout card = Ui.card(c);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        Charts.ScoreRing ring = new Charts.ScoreRing(c, score, label, color, true);
        card.addView(ring, new LinearLayout.LayoutParams(Ui.dp(c, 190), Ui.dp(c, 190)));
        TextView v = Ui.text(c, Double.isNaN(score) ? "" : Scores.verdict((int) score), 18, Ui.scoreColor(score, color), Ui.MEDIUM);
        v.setGravity(Gravity.CENTER);
        card.addView(v);
        if (big != null) {
            TextView b = Ui.text(c, big, 24, color, Ui.MEDIUM);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), 0);
            card.addView(b);
        }
        TextView s = Ui.body(c, sub);
        s.setGravity(Gravity.CENTER);
        s.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), 0);
        card.addView(s);
        if (note != null) {
            TextView n = Ui.text(c, note, 12, Ui.TEXT_3, Ui.REGULAR);
            n.setGravity(Gravity.CENTER);
            n.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), 0);
            card.addView(n);
        }
        // a tile like every other (owner's wish of 18 November): folded it shows a small dial with the score and next to it the
        // word and the big line ("Optimal, 7h 28m asleep", owner's wish of 21 November, #411's time asleep); without a score no
        // dial, the big line, else the line under it
        Tiles.name(card, "score", label + " score", app.openring.core.Layout.dialSummary(score, big, sub));
        return Tiles.dials(card, new double[] {score}, new int[] {color}, new String[] {label});
    }

    static View contributors(Context c, JSONObject score, int color, String title) {
        return contributors(c, score, color, title, null);
    }

    /**
     * The contributor rows. With the day, the temperature row shows the day's stored change exactly as the
     * "Temperature vs usual" card does, with the phone's decimal sign, also for days analysed before the
     * stored detail was built that way (issue #163).
     */
    static View contributors(Context c, JSONObject score, int color, String title, JSONObject day) {
        return contributors(c, score, color, title, day, null);
    }

    /**
     * As above; with the night, the efficiency row shows the night's efficiency worked out from its stored minutes,
     * as Details does, also for nights analysed before issue #107 (the row's score stays the stored one until the
     * night is analysed again).
     */
    static View contributors(Context c, JSONObject score, int color, String title, JSONObject day, JSONObject night) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, title));
        JSONArray cs = score == null ? null : score.optJSONArray("contributors");
        if (cs == null || cs.length() == 0) {
            card.addView(Ui.body(c, "Not enough data yet"));
            return card;
        }
        double dev = Json.d(day, "temp_dev"), eff = nightEfficiency(night);
        // a score from the watch or the phone (issue #379) has texts of its own where the ring's do not fit
        boolean device = score.has("device"), watch = score.optInt("device", -1) == app.openring.core.MainDevice.WATCH;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            String detail = o.optString("detail");
            if ("temperature".equals(o.optString("key")) && !detail.isEmpty() && !Double.isNaN(dev)) detail = signed(dev) + " \u00b0C";
            if ("efficiency".equals(o.optString("key")) && !detail.isEmpty() && !Double.isNaN(eff)) detail = Math.round(eff) + "%";
            String key = o.optString("key"), why = device ? Explain.ofDevice(key, watch) : null;
            card.addView(Ui.contributor(c, o.optString("label"), detail, o.optDouble("score"), color, why != null ? why : Explain.of(key)));
        }
        // folded (owner's wish of 18 November): the contributor that pulls the score down most, as its row rates it
        JSONObject low = null;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            if (o != null && !Double.isNaN(o.optDouble("score")) && (low == null || o.optDouble("score") < low.optDouble("score"))) low = o;
        }
        if (low != null) Tiles.summary(card, "Lowest: " + low.optString("label") + ", " + Scores.rating(low.optDouble("score")).toLowerCase(Locale.getDefault()));
        return card;
    }

    /** The night's efficiency from its stored minutes (issue #107, {@link StageShare#efficiency}); NaN without a night. */
    static double nightEfficiency(JSONObject n) {
        if (n == null) return Double.NaN;
        return StageShare.efficiency(Json.d(n, "asleep_min"), Json.d(n, "in_bed_min"), Json.d(n, "efficiency"));
    }

    static View chartCard(Context c, String title, View chart, String foot) {
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, title));
        card.addView(Ui.gap(c, 8));
        card.addView(chart, Ui.sized(c, -1, 140));
        if (foot != null) {
            card.addView(Ui.gap(c, 6));
            card.addView(Ui.text(c, foot, 12, Ui.TEXT_2, Ui.REGULAR));
        }
        return card;
    }
}
