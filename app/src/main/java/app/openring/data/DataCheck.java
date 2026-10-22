package app.openring.data;

import android.content.Context;
import android.os.Build;
import android.text.format.DateUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.core.EventCodec;
import app.openring.core.Texts;

/**
 * Walks the data pipeline (ring link, decoding, timing, analysis, display) and says where it
 * stops. The report holds counts and dates only, no health values, so it is safe to share.
 */
public final class DataCheck {
    private DataCheck() {}

    public static final class Step {
        public final String title, detail;
        public final int state; // 0 ok, 1 warning, 2 broken

        Step(String title, int state, String detail) {
            this.title = title;
            this.state = state;
            this.detail = detail;
        }
    }

    public static final class Report {
        public final List<Step> steps = new ArrayList<Step>();
        public String text = "";
        public String latestDay;
    }

    static String date(double unix) {
        if (Double.isNaN(unix)) return "--";
        return Instant.ofEpochSecond((long) unix).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    static String mask(String serial) {
        if (serial == null || serial.length() < 6) return serial == null ? "--" : serial;
        return serial.substring(0, 3) + "..." + serial.substring(serial.length() - 3);
    }

    public static Report run(Context c) {
        Db db = Db.get(c);
        Prefs p = Prefs.of(c);
        Report r = new Report();
        StringBuilder t = new StringBuilder();
        t.append("Open Ring data check\n");
        t.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        t.append("Ring ").append(mask(p.serial())).append(", hw ").append(p.hardware()).append(", fw ").append(p.firmware())
                .append(", battery ").append(p.battery()).append("%\n");
        t.append("Last sync ").append(p.lastSync() == 0 ? "never"
                : DateUtils.getRelativeTimeSpanString(p.lastSync() * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                .append(", last error: ").append(p.lastSyncError() == null ? "none" : p.lastSyncError()).append('\n');

        long total = db.count("SELECT COUNT(*) FROM events");
        long dated = db.count("SELECT COUNT(*) FROM events WHERE unix IS NOT NULL");
        long segs = db.count("SELECT COUNT(DISTINCT seg) FROM events WHERE seg IS NOT NULL");
        long datedSegs = db.count("SELECT COUNT(DISTINCT seg) FROM events WHERE unix IS NOT NULL");
        long anchors = db.count("SELECT COUNT(*) FROM events WHERE tag IN (66, 133)");
        long nights = db.count("SELECT COUNT(*) FROM nights");
        long mainNights = db.count("SELECT COUNT(*) FROM nights WHERE main = 1");
        long days = db.count("SELECT COUNT(*) FROM days");
        r.latestDay = db.latestDay();
        String err = db.kv("analysis_error", null);

        // 1. ring link
        if (total == 0) {
            r.steps.add(new Step("Ring link", 2, "No events stored yet. Run a sync with the ring on its charger next to the phone."));
        } else {
            r.steps.add(new Step("Ring link", 0, String.format(Locale.US, "%,d %s copied from the ring", total, Texts.word(total, "event", "events"))));
        }

        // 2. decoding: the streams the analysis needs
        List<double[]> tags = db.tagStats();
        int[][] needed = {{EventCodec.HRV_EVENT, 0}, {EventCodec.ACTIVITY_INFO, 0}, {EventCodec.SLEEP_PERIOD_INFO_2, 0},
            {EventCodec.MOTION_EVENT, 0}, {EventCodec.SLEEP_TEMP_EVENT, 0}, {EventCodec.GREEN_IBI_QUALITY, 0}, {EventCodec.BEDTIME_PERIOD, 0}};
        for (double[] ts : tags) for (int[] nd : needed) if ((int) ts[0] == nd[0]) nd[1] = (int) ts[1];
        StringBuilder have = new StringBuilder(), miss = new StringBuilder();
        for (int[] nd : needed) {
            StringBuilder sb = nd[1] > 0 ? have : miss;
            if (sb.length() > 0) sb.append(", ");
            sb.append(EventCodec.name(nd[0]));
            if (nd[1] > 0) sb.append(" (").append(nd[1]).append(')');
        }
        if (total > 0) {
            boolean core = needed[0][1] > 0 || needed[1][1] > 0 || needed[2][1] > 0;
            r.steps.add(new Step("Measurement data", core ? (miss.length() > 0 ? 1 : 0) : 2,
                    (have.length() > 0 ? "Found: " + have : "None of the measurement streams were found")
                            + (miss.length() > 0 ? ". Missing: " + miss : "")
                            + (core ? "" : ". The ring sent only housekeeping events; wear it for a few hours, then sync again.")));
        }

        // 3. timing
        if (total > 0) {
            if (dated == 0) {
                r.steps.add(new Step("Placing data in time", 2, String.format(Locale.US,
                        "0 of %,d %s could be dated (%s in %s). The ring clock is a counter "
                                + "that only becomes a date through a time record. Sync again and let it finish completely "
                                + "with the ring nearby; the end of a finished sync adds one.", total, Texts.word(total, "event", "events"),
                        Texts.count(anchors, "time record", "time records"), Texts.count(segs, "boot segment", "boot segments"))));
            } else {
                int st = dated * 10 < total * 9 ? 1 : 0;
                r.steps.add(new Step("Placing data in time", st, String.format(Locale.US,
                        "%,d of %,d %s dated (%.0f%%), %d of %s with a time record%s", dated, total, Texts.word(total, "event", "events"),
                        100.0 * dated / total, datedSegs, Texts.count(segs, "boot segment", "boot segments"),
                        st == 1 ? ". Undated parts are from ring restarts without a time record and are skipped." : "")));
            }
        }

        // 4. analysis
        if (dated > 0) {
            String detail = String.format(Locale.US, "%s (%s), %s computed%s", Texts.count(nights, "sleep period", "sleep periods"),
                    Texts.count(mainNights, "main night", "main nights"), Texts.count(days, "day", "days"),
                    r.latestDay == null ? "" : ", latest day " + r.latestDay);
            int st = err != null ? 2 : (days == 0 ? 2 : (mainNights == 0 ? 1 : 0));
            if (err != null) detail += ". Analysis error: " + err;
            else if (days == 0) detail += ". Nothing was computed although data is dated; use Repair below and send this report.";
            else if (mainNights == 0) detail += ". No night found yet; sleep appears after the ring was worn overnight and synced in the morning.";
            r.steps.add(new Step("Analysis", st, detail));
        }

        t.append('\n');
        for (Step s : r.steps) {
            t.append(s.state == 0 ? "[ok]   " : s.state == 1 ? "[note] " : "[FAIL] ").append(s.title).append(": ").append(s.detail).append('\n');
        }
        t.append("\nEvent types (count, dated, first day, last day)\n");
        for (double[] ts : tags) {
            t.append(String.format(Locale.US, "  %-34s %8d %8d  %s  %s%n", EventCodec.name((int) ts[0]), (long) ts[1], (long) ts[2], date(ts[3]), date(ts[4])));
        }
        t.append("\nTime records: ").append(anchors).append(", boot segments: ").append(segs).append(", sync epoch ")
                .append(db.kvLong("epoch", 1)).append(", cursor ").append(db.kvLong("cursor", 0)).append('\n');
        // the nights themselves: which day each belongs to is what the screens go by
        List<String> ns = db.nightsJson(java.time.LocalDate.now().minusDays(14).toString(), "9999-12-31", false);
        if (!ns.isEmpty()) {
            t.append("\nSleep periods (day, from, to, asleep, score, stages)\n");
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US);
            for (String js : ns) {
                org.json.JSONObject o = Json.parse(js);
                if (o == null) continue;
                org.json.JSONObject sc = o.optJSONObject("score");
                t.append(String.format(Locale.US, "  %s %s  %s  %s  %4.0f min  %s  %s%n", o.optString("day"),
                        o.optBoolean("main") ? "main" : "nap ",
                        hm.format(new java.util.Date((long) (o.optDouble("start", 0) * 1000))),
                        hm.format(new java.util.Date((long) (o.optDouble("end", 0) * 1000))),
                        o.optDouble("asleep_min", 0),
                        sc == null || !sc.has("value") ? "--" : String.valueOf(sc.optInt("value")),
                        o.optString("source", "?")));
            }
        }
        r.text = t.toString();
        return r;
    }
}
