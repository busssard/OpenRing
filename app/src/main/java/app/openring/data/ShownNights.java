package app.openring.data;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

import app.openring.core.DeviceHistory;
import app.openring.core.MainDevice;
import app.openring.core.Texts;
import app.openring.core.withings.WatchDays;

/**
 * The main night of each day as the screens show it, from the main device and filled by the other (owner's report of 14
 * November: with only the watch the Screen tab showed no sleep, since it read only the ring's nights). One source for every
 * route that needs a day's night besides the Sleep tab: the Screen tab (its 14 days, the clock, the first look, the hour
 * before bed, the night and the score's night part) and the badges. Each day takes the night {@link MainDevice#nightFrom}
 * picks, the rule of the Sleep tab: the watch's when it is the main device or the ring has none.
 *
 * <p>A day without a device night takes the night entered by hand, if any (issue #427, {@link #withHand}).
 *
 * <p>A watch night is Withings' own night in the ring's night fields ({@link WatchHistory#nightJson}), worked out on each read
 * from the stored rows; nothing is stored. With only the ring (no watch row) the list is exactly {@link Db#nightsJson} of the
 * main nights, parsed, so every ring number stays as it was.
 */
public final class ShownNights {
    private ShownNights() {}

    /** Which devices a day is shown with: worked out by the day load from the settings and what is paired, never stored. */
    public static final class Devices {
        /** The main device ({@link MainDevice#RING}, {@code WATCH} or {@code PHONE}) as the day load shows it. */
        public final int main;
        /** The ring's results are shown (false only for the demo shown as without a ring). */
        public final boolean ring;
        /** The watch's rows are read (a watch is or was added; false for the demo shown as only the phone). */
        public final boolean watch;
        /**
         * The device whose days stand in for the ring's day summaries ({@link DeviceHistory#device}): the watch, else the phone,
         * only without a ring; -1 with a ring, whose own scores and summaries stay.
         */
        public final int history;
        /** The watch's step goal and the profile's age, for the scores of the device's days (as the dials). */
        public final int goal;
        public final double age;

        public Devices(int main, boolean ring, boolean watch, int history, int goal, double age) {
            this.main = main;
            this.ring = ring;
            this.watch = watch;
            this.history = history;
            this.goal = goal;
            this.age = age;
        }

        /** Only the ring's results, as before the watch: callers without a choice (and the tests of the ring's own numbers). */
        public static final Devices RING = new Devices(MainDevice.RING, true, false, -1, 0, Double.NaN);
    }

    /** {@code zones} with {@code day} in {@code zone}: the shown day in the zone its dial and Sleep tab have. */
    public static DeviceHistory.DayZones with(final DeviceHistory.DayZones zones, final LocalDate day, final ZoneId zone) {
        return new DeviceHistory.DayZones() {
            @Override
            public ZoneId of(LocalDate d) {
                return d.equals(day) ? zone : zones.of(d);
            }
        };
    }

    /**
     * The main nights of the days from {@code first} to {@code last} as the screens show them, ordered by start: per day the
     * ring's main nights or the watch's night, as {@link MainDevice#nightFrom} picks. {@code zones}: the zone of each day, for
     * the watch's nights (a watch night belongs to the day its end falls on there). Without a watch row (or with
     * {@code dev.watch} false) exactly the ring's main nights of {@link Db#nightsJson}, parsed, unreadable ones left out.
     */
    public static List<JSONObject> main(Db db, LocalDate first, LocalDate last, Devices dev, DeviceHistory.DayZones zones) {
        List<JSONObject> devices = devices(db, first, last, dev, zones);
        // only the phone (no ring, no watch): no night at all, as its Sleep tab (sleep needs a ring or a watch)
        if (dev.history == MainDevice.PHONE) return devices;
        return withHand(db, devices, first, last, zones, dev.age);
    }

    /**
     * {@code shown} (the devices' nights, ordered by start) with the nights entered by hand (issue #427) of the days that have no
     * device night, and whose times overlap none of them: the third source of {@link MainDevice#nightFrom}, after the ring and
     * the watch. Without a night by hand in the range, {@code shown} itself, unchanged.
     */
    static List<JSONObject> withHand(Db db, List<JSONObject> shown, LocalDate first, LocalDate last, DeviceHistory.DayZones zones, double age) {
        if (!HandNights.any(db)) return shown;
        TreeMap<LocalDate, JSONObject> hand = HandNights.days(db, first, last, zones, age);
        if (hand.isEmpty()) return shown;
        Set<String> days = new HashSet<String>();
        List<double[]> spans = new ArrayList<double[]>();
        for (JSONObject o : shown) {
            days.add(o.optString("day"));
            spans.add(new double[] {Json.d(o, "start"), Json.d(o, "end")});
        }
        List<JSONObject> out = new ArrayList<JSONObject>(shown);
        boolean added = false;
        for (java.util.Map.Entry<LocalDate, JSONObject> e : hand.entrySet()) {
            JSONObject o = e.getValue();
            boolean device = days.contains(e.getKey().toString());
            if (MainDevice.nightFrom(MainDevice.RING, device, false, true) != MainDevice.HAND) continue;
            if (app.openring.core.HandNight.overlaps(Json.d(o, "start"), Json.d(o, "end"), spans)) continue;
            out.add(o);
            added = true;
        }
        if (added) byStart(out);
        return out;
    }

    private static void byStart(List<JSONObject> out) {
        // the sort is stable, so the ring's own order stays
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(Json.d(a, "start"), Json.d(b, "start"));
            }
        });
    }

    /** The devices' nights of {@link #main}, without the nights by hand. */
    static List<JSONObject> devices(Db db, LocalDate first, LocalDate last, Devices dev, DeviceHistory.DayZones zones) {
        List<JSONObject> ring = new ArrayList<JSONObject>();
        if (dev.ring) {
            for (String js : db.nightsJson(first.toString(), last.toString(), true)) {
                JSONObject o = Json.parse(js);
                if (o != null) ring.add(o);
            }
        }
        if (!dev.watch || !WatchStore.any(db)) return ring;
        TreeMap<LocalDate, JSONObject> watch = watch(db, first, last, zones, dev.age);
        if (watch.isEmpty()) return ring;
        Set<String> ringDays = new HashSet<String>();
        for (JSONObject o : ring) ringDays.add(o.optString("day"));
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject o : ring) {
            boolean watchToo = watch.containsKey(BadgeDays.parse(o.optString("day")));
            if (MainDevice.nightFrom(dev.main, true, watchToo) == MainDevice.RING) out.add(o);
        }
        boolean added = false;
        for (JSONObject o : watch.values()) {
            if (MainDevice.nightFrom(dev.main, ringDays.contains(o.optString("day")), true) != MainDevice.WATCH) continue;
            out.add(o);
            added = true;
        }
        if (!added) return out;
        // by start, as the ring's list
        byStart(out);
        return out;
    }

    /**
     * The watch's night of each day from {@code first} to {@code last} (days without one missing), each in its day's zone, with
     * REM where the watch tells it, its score and lowest heart rate, as the Sleep tab's strip has them. Reads only the sleep
     * pieces and heart rates, from two days before the first day to a day after the last.
     */
    public static TreeMap<LocalDate, JSONObject> watch(Db db, LocalDate first, LocalDate last, DeviceHistory.DayZones zones, double age) {
        TreeMap<LocalDate, JSONObject> out = new TreeMap<LocalDate, JSONObject>();
        double from = first.atStartOfDay(zones.of(first)).toEpochSecond() - 2 * 86400.0;
        double to = last.plusDays(1).atStartOfDay(zones.of(last)).toEpochSecond() + 86400.0;
        MainDevice.Watch w = WatchStore.nightRows(db, from, to);
        if (w.sleep.isEmpty()) return out;
        WatchDays.Rem rem = WatchStore.rem(db);
        for (DeviceHistory.NightRow r : DeviceHistory.nightRows(w.sleep, w.heart, first, last, zones, age, rem)) {
            out.put(r.day, WatchHistory.nightJson(r));
        }
        return out;
    }

    /**
     * The day (in {@code zone}) of the first record of {@code device}: the watch's first step, sleep or heart rate record, the
     * phone's first step interval; null for none or another device.
     */
    public static LocalDate firstDay(Db db, int device, ZoneId zone) {
        long t = -1;
        if (device == MainDevice.WATCH) {
            t = db.count("SELECT COALESCE(MIN(m), -1) FROM (SELECT MIN(start) AS m FROM watch_steps UNION ALL SELECT MIN(start) FROM watch_sleep"
                    + " UNION ALL SELECT MIN(t) FROM watch_hr)");
        } else if (device == MainDevice.PHONE) {
            t = db.count("SELECT COALESCE(MIN(start), -1) FROM phone_steps");
        }
        return t > 0 ? java.time.Instant.ofEpochSecond(t).atZone(zone).toLocalDate() : null;
    }

    /** Days the Data check lists the watch's nights for, today included (as the ring's sleep periods, issue #40). */
    public static final int CHECK_DAYS = 14;

    /**
     * The Data check's lines on the watch's nights of the last {@link #CHECK_DAYS} days (owner's report of 14 November): how many
     * and which days, the days the ring has a main night too, nothing else (no times, minutes or scores), so it stays safe to
     * share. Empty without any watch sleep piece.
     */
    public static String checkLines(Db db, LocalDate today, ZoneId phone) {
        if (db.count("SELECT EXISTS (SELECT 1 FROM watch_sleep)") == 0) return "";
        LocalDate first = today.minusDays(CHECK_DAYS - 1);
        TreeMap<LocalDate, JSONObject> watch = watch(db, first, today, WatchHistory.dayZones(db, phone), Double.NaN);
        Set<String> ringDays = new HashSet<String>();
        for (String js : db.nightsJson(first.toString(), today.toString(), true)) {
            JSONObject o = Json.parse(js);
            if (o != null) ringDays.add(o.optString("day"));
        }
        StringBuilder b = new StringBuilder();
        b.append("\nWatch nights of the last ").append(CHECK_DAYS).append(" days (counts and dates only)\n");
        b.append("  ").append(Texts.count(watch.size(), "night", "nights"));
        int both = 0;
        for (LocalDate d : watch.keySet()) if (ringDays.contains(d.toString())) both++;
        b.append(String.format(Locale.US, ", %d of them on a day the ring has a main night too\n", both));
        b.append("  Per day:\n");
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            boolean w = watch.containsKey(d), r = ringDays.contains(d.toString());
            b.append("    ").append(d).append(": ").append(w && r ? "watch and ring" : w ? "watch" : r ? "ring" : "none").append('\n');
        }
        return b.toString();
    }
}
