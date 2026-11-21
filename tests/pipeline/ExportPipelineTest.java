import android.content.Context;

import org.json.JSONObject;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import app.openring.data.Db;
import app.openring.data.Exporter;

/**
 * The daily CSV's zone of each day (left_out_min counts a day midnight to midnight in it): {@code Exporter.DayZones}, which
 * reads every day's zone in two queries, gives the same zone as the one day rule {@code Exporter.dayZone} (its night's, else
 * the zone noted at its noon, else the phone's) for every day, and the file's left_out_min column is the one that rule
 * gives. Synthetic data only. Run by tests/run_pipeline.sh after TrendsPipelineTest.
 */
public class ExportPipelineTest {
    static int passed, failed;

    static void check(String name, boolean ok, Object info) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (info == null ? "" : "  (" + info + ")"));
    }

    static Context freshDb(String name) throws Exception {
        java.lang.reflect.Field f = Db.class.getDeclaredField("instance");
        f.setAccessible(true);
        Db open = (Db) f.get(null);
        if (open != null) open.close();
        f.set(null, null);
        return new Context(Files.createTempDirectory(name).toFile());
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Export: the zone of each day");
        java.util.TimeZone keep = java.util.TimeZone.getDefault();
        try {
            trip("Europe/Zurich");
            trip("America/New_York");
            years();
        } finally {
            java.util.TimeZone.setDefault(keep);
        }
        System.out.println("export: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static final ZoneId ZH = ZoneId.of("Europe/Zurich"), NY = ZoneId.of("America/New_York"), TK = ZoneId.of("Asia/Tokyo");

    /** A main night of {@code day} from 23:00 the evening before to 07:00 in {@code z}, its JSON as given. */
    static Object[] night(LocalDate day, ZoneId z, int minutesLater, String json) {
        double start = day.minusDays(1).atTime(23, 0).atZone(z).toEpochSecond() + minutesLater * 60;
        return new Object[] {start, start + 8 * 3600, day.toString(), 1, 80, json};
    }

    static String zoned(String zone) throws Exception {
        return new JSONObject().put("main", true).put("zone", zone).put("stages", "1111222233334444").toString();
    }

    /**
     * Six weeks of a trip, Zurich, New York, Tokyo and back, with every kind of night the rule tells apart: in a known zone,
     * without a zone, in a zone this phone does not know, with a JSON null zone, JSON that does not read, two main nights on
     * one day (the later one counts), a nap that is not main, days without a night, the notes with a gap, and left out times on several days, with the phone in {@code phoneId}.
     */
    static void trip(String phoneId) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(phoneId));
        ZoneId phone = ZoneId.of(phoneId);
        Context c = freshDb("openring-export-zones");
        Db db = Db.get(c);
        LocalDate first = LocalDate.parse("2026-08-01"), last = first.plusDays(41);
        List<Object[]> nights = new ArrayList<Object[]>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            int i = (int) java.time.temporal.ChronoUnit.DAYS.between(first, d);
            ZoneId z = i < 12 ? ZH : i < 24 ? NY : i < 32 ? TK : ZH;
            db.putDay(d.toString(), new JSONObject().put("date", d.toString()).put("sleep_score", 70 + i % 20).put("steps", 5000 + 100 * i).toString());
            // the notes: every morning, except days 18 to 21 (a gap) and from day 38 on (after the newest note)
            if ((i < 18 || i > 21) && i < 38) db.noteZone(d.atTime(8, 0).atZone(z).toEpochSecond(), z.getId());
            if (i % 7 == 3) continue; // no night that day
            String json;
            switch (i % 9) {
                case 1: json = new JSONObject().put("main", true).put("stages", "1234").toString(); break; // no zone
                case 2: json = zoned("Mars/Olympus"); break;
                case 4: json = new JSONObject().put("main", true).put("zone", JSONObject.NULL).toString(); break;
                case 5: json = "{not json"; break;
                default: json = zoned(z.getId());
            }
            nights.add(night(d, z, 0, json));
            // two main nights on some days: the later one (by start) counts, slept in Tokyo
            if (i % 5 == 0) nights.add(night(d, z, 120, zoned("Asia/Tokyo")));
            // and a nap in Los Angeles that is not main, which never counts
            if (i % 6 == 0) {
                Object[] nap = night(d, z, 15 * 60, zoned("America/Los_Angeles"));
                nap[3] = 0;
                nights.add(nap);
            }
        }
        db.replaceNights(0, 1e12, nights);
        // left out: an evening in Zurich, a whole day in New York, an afternoon in Tokyo, the last evening
        double[][] left = {{at(first.plusDays(5), 19, ZH), at(first.plusDays(5), 23, ZH)}, {at(first.plusDays(15), 0, NY), at(first.plusDays(16), 0, NY)},
            {at(first.plusDays(26), 13, TK), at(first.plusDays(26), 17, TK)}, {at(last, 20, ZH), at(last.plusDays(1), 2, ZH)}};
        for (int k = 0; k < left.length; k++) db.addExcluded(left[k][0], left[k][1], 1000 + k);

        String how = " (phone in " + phoneId + ")";
        Object zones = newDayZones(db, phone);
        int days = 0, wrong = 0, fromNight = 0, other = 0;
        StringBuilder firstWrong = new StringBuilder();
        List<String> keys = new ArrayList<String>();
        for (LocalDate d = first.minusDays(3); !d.isAfter(last.plusDays(3)); d = d.plusDays(1)) keys.add(d.toString());
        keys.add("not-a-day");
        keys.add("");
        for (String day : keys) {
            ZoneId want = oneDay(db, day, phone), got = of(zones, day);
            if (!want.equals(phone)) other++;
            if (!want.equals(got)) {
                wrong++;
                if (firstWrong.length() == 0) firstWrong.append(day).append(": ").append(got).append(" not ").append(want);
            }
            days++;
        }
        for (Object[] n : nights) if ((Integer) n[3] == 1 && ((String) n[5]).contains("Tokyo")) fromNight++;
        check("the zone of every day read at once is the one day rule's" + how, wrong == 0, days + " days, " + wrong + " wrong " + firstWrong);
        check("... and the trip has days in other zones than the phone's, from nights in Tokyo too" + how, other >= 20 && fromNight >= 8,
                other + " days not in the phone's zone, " + fromNight + " nights in Tokyo");

        // the file: left_out_min is the one day rule's, and some day counts differently in its own zone than in the phone's
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Exporter.dailyCsv(c, out);
        String[] lines = new String(out.toByteArray(), "UTF-8").split("\n", -1);
        app.openring.core.analysis.LeftOut lo = db.leftOut();
        int rows = 0, bad = 0, counted = 0, differs = 0;
        StringBuilder firstBad = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] cells = lines[i].split(",", -1);
            String day = cells[0];
            long want = leftOutMin(lo, day, oneDay(db, day, phone)), inPhone = leftOutMin(lo, day, phone);
            long got = Long.parseLong(cells[cells.length - 1]);
            if (got != want) {
                bad++;
                if (firstBad.length() == 0) firstBad.append(day).append(": ").append(got).append(" not ").append(want);
            }
            if (want > 0) counted++;
            if (want != inPhone) differs++;
            rows++;
        }
        check("the file's left_out_min is the one day rule's on every row" + how, bad == 0 && rows == 42, rows + " rows, " + bad + " wrong " + firstBad);
        check("... with left out minutes on several days, some counted differently than in the phone's zone" + how, counted >= 3 && differs >= 2,
                counted + " days with minutes, " + differs + " different in the phone's zone");
    }

    /** Three years of nights and notes with one left out evening: the same zones and file, with far fewer reads. */
    static void years() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Zurich"));
        ZoneId phone = ZoneId.of("Europe/Zurich");
        Context c = freshDb("openring-export-years");
        Db db = Db.get(c);
        LocalDate first = LocalDate.parse("2023-11-01"), last = LocalDate.parse("2026-10-31");
        List<Object[]> nights = new ArrayList<Object[]>();
        StringBuilder stages = new StringBuilder();
        for (int k = 0; k < 960; k++) stages.append((char) ('1' + k % 4));
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            ZoneId z = d.getMonthValue() == 7 ? NY : ZH;
            db.putDay(d.toString(), new JSONObject().put("date", d.toString()).put("sleep_score", 80).put("steps", 7000).toString());
            db.noteZone(d.atTime(8, 0).atZone(z).toEpochSecond(), z.getId());
            nights.add(night(d, z, 0, new JSONObject().put("main", true).put("zone", z.getId()).put("stages", stages.toString()).toString()));
        }
        db.replaceNights(0, 1e12, nights);
        db.addExcluded(at(LocalDate.parse("2026-07-10"), 18, NY), at(LocalDate.parse("2026-07-10"), 23, NY), 1);

        int wrong = 0, n = 0;
        List<ZoneId> bulk = new ArrayList<ZoneId>();
        long atOnce = Long.MAX_VALUE, perDay = Long.MAX_VALUE;
        // three rounds each, the best counts (the first round also warms up the JVM)
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            Object zones = newDayZones(db, phone);
            bulk.clear();
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) bulk.add(of(zones, d.toString()));
            long t1 = System.nanoTime();
            int i = 0;
            wrong = 0;
            n = 0;
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                if (!oneDay(db, d.toString(), phone).equals(bulk.get(i++))) wrong++;
                n++;
            }
            long t2 = System.nanoTime();
            atOnce = Math.min(atOnce, t1 - t0);
            perDay = Math.min(perDay, t2 - t1);
        }
        check("three years: every day's zone read at once is the one day rule's", wrong == 0 && n == 1096, n + " days, " + wrong + " wrong");
        System.out.println("  info three years of days: read at once " + atOnce / 1000000 + " ms (2 queries), one day at a time "
                + perDay / 1000000 + " ms (" + 2 * n + " queries or more)");

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long t3 = System.nanoTime();
        Exporter.dailyCsv(c, out);
        long ms = (System.nanoTime() - t3) / 1000000;
        String csv = new String(out.toByteArray(), "UTF-8");
        check("three years: the file has a row per day and the left out evening on its New York day", csv.split("\n").length == 1097
                && csv.contains("\n2026-07-10,") && csv.split("\n2026-07-10,")[1].split("\n")[0].endsWith(",300"), ms + " ms");
    }

    static double at(LocalDate d, int hour, ZoneId z) {
        return d.atTime(hour, 0).atZone(z).toEpochSecond();
    }

    // ---------------------------------------------------------------- the package private parts of Exporter

    static ZoneId oneDay(Db db, String day, ZoneId phone) throws Exception {
        java.lang.reflect.Method m = Exporter.class.getDeclaredMethod("dayZone", Db.class, String.class, ZoneId.class);
        m.setAccessible(true);
        return (ZoneId) m.invoke(null, db, day, phone);
    }

    static Object newDayZones(Db db, ZoneId phone) throws Exception {
        java.lang.reflect.Constructor<?> k = Class.forName("app.openring.data.Exporter$DayZones").getDeclaredConstructor(Db.class, ZoneId.class);
        k.setAccessible(true);
        return k.newInstance(db, phone);
    }

    static ZoneId of(Object zones, String day) throws Exception {
        java.lang.reflect.Method m = zones.getClass().getDeclaredMethod("of", String.class);
        m.setAccessible(true);
        return (ZoneId) m.invoke(zones, day);
    }

    static long leftOutMin(app.openring.core.analysis.LeftOut left, String day, ZoneId z) throws Exception {
        java.lang.reflect.Method m = Exporter.class.getDeclaredMethod("leftOutMin", app.openring.core.analysis.LeftOut.class, String.class, ZoneId.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, left, day, z);
    }
}
