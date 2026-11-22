import android.content.Context;

import org.json.JSONObject;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import app.openring.core.MainDevice;
import app.openring.core.RingEvent;
import app.openring.core.analysis.ClockAverage;
import app.openring.core.analysis.Stats;
import app.openring.core.analysis.TS;
import app.openring.core.analysis.Trends;
import app.openring.data.Analyzer;
import app.openring.data.DaySeries;
import app.openring.data.Db;
import app.openring.data.Demo;
import app.openring.data.Json;
import app.openring.data.Periods;
import app.openring.data.Prefs;
import app.openring.data.ScreenDays;
import app.openring.data.ShownNights;
import app.openring.data.SleepHistory;
import app.openring.data.TrendDays;

/**
 * Trends (issue #374) on the JVM: the shared day series, screen time per day, the per day values Trends reads from a
 * database for the ring, the watch and the phone, the demo year. Run by tests/run_pipeline.sh after PipelineTest.
 */
public class TrendsPipelineTest {
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
        System.out.println("Trends (issue #374)");
        daySeries();
        fields();
        demoHistoryPinned();
        screenPerDay();
        demoYear();
        clockChangeAndTrip();
        realReanalyse();
        stamp();
        threeYears();
        periodRingNights();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** The two helpers before #374 (Pages.daysSeries, HistoryViews.series), the same loop each: the reference. */
    static TS oldSeries(LocalDate date, double[] v) {
        TS ts = new TS();
        int n = v.length;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(v[i])) continue;
            double t = date.minusDays(n - 1 - i).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() + 43200;
            ts.add(t, v[i]);
        }
        return ts;
    }

    /** DaySeries gives exactly the series of the two helpers it replaces, also across both clock changes and with gaps. */
    static void daySeries() {
        Random r = new Random(374);
        int same = 0, cases = 0;
        String[] ends = {"2026-03-30", "2026-10-26", "2026-11-18", "2024-02-29"};
        for (String e : ends) {
            for (int n : new int[] {1, 7, 30, 31}) {
                double[] v = new double[n];
                for (int i = 0; i < n; i++) v[i] = r.nextInt(4) == 0 ? Double.NaN : r.nextDouble() * 100 - 20;
                TS a = oldSeries(LocalDate.parse(e), v), b = DaySeries.of(LocalDate.parse(e), v);
                boolean eq = a.size() == b.size();
                for (int i = 0; eq && i < a.size(); i++) eq = Double.doubleToLongBits(a.t(i)) == Double.doubleToLongBits(b.t(i))
                        && Double.doubleToLongBits(a.v(i)) == Double.doubleToLongBits(b.v(i));
                if (eq) same++;
                cases++;
            }
        }
        check("#374 DaySeries: the same times and values as Pages.daysSeries and HistoryViews.series", same == cases, same + " of " + cases);
        TS z = DaySeries.of(LocalDate.parse("2026-03-29"), new double[] {1}, ZoneId.of("Europe/Zurich"));
        check("#374 DaySeries: a day's value at local midnight plus 12 hours (the clock change day too)",
                z.t(0) == LocalDate.parse("2026-03-29").atStartOfDay(ZoneId.of("Europe/Zurich")).toEpochSecond() + 43200, z.t(0));
        check("#374 DaySeries: only NaN gives an empty series", DaySeries.of(LocalDate.parse("2026-03-29"), new double[] {Double.NaN, Double.NaN}).isEmpty(), null);
    }

    /** The top level fields of a stored night or day, read without parsing its series, as org.json reads them. */
    static void fields() {
        String js = "{\"a\": 1.5, \"s\":\"x,}\\\"y\", \"o\":{\"value\":80,\"c\":[{\"d\":\"7h }] 31m\"}]},\"arr\":[[1,2],[3,4]],\"n\":null,\"z\":\"Europe/Zurich\" }";
        Map<String, String> f = TrendDays.fields(js);
        JSONObject o = Json.parse(js);
        check("#374 fields: numbers, strings with commas, quotes and braces, nested objects and arrays", f != null && f.size() == 6
                && TrendDays.num(f.get("a")) == 1.5 && f.get("s").equals("\"x,}\\\"y\"") && TrendDays.num(TrendDays.fields(f.get("o")).get("value")) == 80
                && f.get("arr").equals("[[1,2],[3,4]]") && Double.isNaN(TrendDays.num(f.get("n"))) && "Europe/Zurich".equals(TrendDays.text(f.get("z")))
                && o != null && o.optJSONObject("o").optInt("value") == 80, f);
        check("#374 fields: an empty object", TrendDays.fields(" { } ").isEmpty(), null);
        String[] bad = {null, "", "[1]", "{\"a\" 1}", "{\"a\":1", "{\"a\":\"x}", "{a:1}", "{\"a\":[1,2}", "{\"a\":1;\"b\":2}", "{\"a\":{\"b\":\"x}}"};
        int refused = 0;
        for (String b : bad) if (TrendDays.fields(b) == null) refused++;
        check("#374 fields: anything that is not a whole object gives null (the night is skipped)", refused == bad.length, refused + " of " + bad.length);
        // Android's org.json writes "/" as "\/" (code review of #413): a stored zone reads as the zone, every escape as its character
        check("#374 text: escaped slashes in zones", "Europe/Zurich".equals(TrendDays.text("\"Europe\\/Zurich\""))
                && "America/New_York".equals(TrendDays.text("\"America\\/New_York\"")), TrendDays.text("\"Europe\\/Zurich\""));
        check("#374 text: the zone it gives is known", app.openring.core.analysis.ClockChange.zone(TrendDays.text("\"Europe\\/Zurich\"")) != null, null);
        String esc = TrendDays.text("\"a\\\"b\\\\c\\nd\\te\\rf\\bg\\fh\\u00e9i\\u12\"");
        check("#374 text: quote, backslash, line breaks, tabs and \\u escapes read as org.json reads them; a broken \\u kept",
                esc.equals("a\"b\\c\nd\te\rf\bg\fh\u00e9i\\u12") && esc.equals(Json.parse("{\"x\":\"a\\\"b\\\\c\\nd\\te\\rf\\bg\\fh\\u00e9i\\\\u12\"}").optString("x")), esc.length());
        check("#374 fields: a text or object is not a number", Double.isNaN(TrendDays.num("\"5\"")) && Double.isNaN(TrendDays.num("{}")) && Double.isNaN(TrendDays.num("[1]"))
                && Double.isNaN(TrendDays.num("")) && Double.isNaN(TrendDays.num(null)) && Double.isNaN(TrendDays.num("x1")) && TrendDays.text("5").equals("5"), null);
    }

    /**
     * The tests' own histories and the demo's raw history stay byte for byte as before #374 (the demo year adds results only):
     * a digest of every event of {@code Demo.history} for fixed dates and seeds, as on dev before the change.
     */
    static void demoHistoryPinned() throws Exception {
        ZoneId z = ZoneId.of("Europe/Zurich");
        long t0 = LocalDate.parse("2026-10-14").atStartOfDay(z).toEpochSecond();
        String a = digest(Demo.history(t0, 35, t0 + 35 * 86400L + 9 * 3600, z, new Random(7))),
                b = digest(Demo.history(t0, 10, t0 + 11 * 86400L, z, new Random(21)));
        System.out.println("    #374 Demo.history digests " + a + " " + b);
        check("#374 Demo.history of the demo (35 days, seed 7) is unchanged", PIN_DEMO.equals(a), a);
        check("#374 Demo.history of a test history (10 days, seed 21) is unchanged", PIN_TEST.equals(b), b);
    }

    /** SHA-256 of the events (tag, ds, body), computed on dev before #374. */
    static final String PIN_DEMO = "3c63f885b0fa2f9c", PIN_TEST = "729f0df8a95e2fb9";

    static String digest(List<RingEvent> ev) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        for (RingEvent e : ev) {
            md.update((byte) e.tag);
            for (int i = 0; i < 8; i++) md.update((byte) (e.ds >>> (8 * i)));
            md.update(e.body);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.substring(0, 16);
    }

    /** A day's zone as the day load gives the Screen tab (its ring night's, else the noted one at noon, else the phone's). */
    static ZoneId screenZone(Db db, LocalDate d, ZoneId phone) {
        JSONObject night = null;
        for (String js : db.nightsJson(d.toString(), d.toString(), true)) night = Json.parse(js);
        return Json.dayZone(night, db.zoneAt(Json.noon(d, phone)), phone);
    }

    /**
     * Screen time per day (ScreenDays.perDay, Trends) counts like the Screen tab ({@code info(...).day.totalMin}) on 60 days
     * with an app left out, a trip to New York and back, a gap in reading the usage log and both clock changes.
     */
    static void screenPerDay() throws Exception {
        Context c = freshDb("trends-screen");
        Db db = Db.get(c);
        ZoneId zh = ZoneId.of("Europe/Zurich"), ny = ZoneId.of("America/New_York");
        LocalDate first = LocalDate.parse("2026-03-01");
        Random r = new Random(374);
        List<double[]> s = new ArrayList<double[]>();
        Map<String, List<double[]>> apps = new HashMap<String, List<double[]>>();
        apps.put("demo.maps", new ArrayList<double[]>());
        apps.put("demo.chat", new ArrayList<double[]>());
        for (int d = 0; d < 62; d++) {
            LocalDate day = first.plusDays(d);
            boolean away = d >= 20 && d < 30;
            ZoneId z = away ? ny : zh;
            db.noteZone(day.atTime(8, 0).atZone(z).toEpochSecond(), z.getId());
            for (int k = 0; k < 25; k++) {
                double a = day.atStartOfDay(z).toEpochSecond() + r.nextInt(86400) - (k == 0 ? 600 : 0), b = a + 30 + r.nextInt(1800);
                s.add(new double[] {a, b});
                apps.get(r.nextBoolean() ? "demo.maps" : "demo.chat").add(new double[] {a, b - 5});
            }
            if (d == 40) s.add(new double[] {day.atTime(23, 50).atZone(z).toEpochSecond(), day.plusDays(1).atTime(0, 40).atZone(z).toEpochSecond()});
        }
        db.putScreen(s);
        db.putApps(apps);
        double start = first.atStartOfDay(zh).toEpochSecond(), now = first.plusDays(61).atTime(9, 0).atZone(zh).toEpochSecond();
        // the usage log was not read for two days in the middle
        db.putScreenRead(start + 86400, first.plusDays(44).atStartOfDay(zh).toEpochSecond());
        db.putScreenRead(first.plusDays(46).atStartOfDay(zh).toEpochSecond(), now);
        Set<String> out = new TreeSet<String>();
        out.add("demo.maps");
        ZoneId phone = ZoneId.systemDefault();
        LocalDate from = first, to = first.plusDays(59);
        Map<LocalDate, ZoneId> zones = new HashMap<LocalDate, ZoneId>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) zones.put(d, screenZone(db, d, phone));
        final Map<LocalDate, ZoneId> zz = zones;
        double[] per = ScreenDays.perDay(db, from, to, out, now, new app.openring.core.DeviceHistory.DayZones() {
            @Override
            public ZoneId of(LocalDate day) {
                return zz.get(day);
            }
        });
        List<Trends.Day> td = TrendDays.read(db, to, now, out, ShownNights.Devices.RING, phone);
        int same = 0, unknown = 0, sameTrends = 0, n = 0;
        String bad = "";
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1), n++) {
            ScreenDays.Info in = ScreenDays.info(db, d, zones.get(d), 120, out, 0, now);
            double want = in.day == null ? Double.NaN : in.day.totalMin;
            if (Double.isNaN(want)) unknown++;
            if (Double.isNaN(want) ? Double.isNaN(per[n]) : Math.abs(want - per[n]) < 1e-9) same++;
            else bad += d + " " + want + " " + per[n] + "; ";
            Trends.Day t = null;
            for (Trends.Day x : td) if (x.date.equals(d)) t = x;
            double got = t == null ? Double.NaN : t.v[Trends.SCREEN];
            if (d.equals(to)) sameTrends++; // today: Trends reads it, the chart leaves it out
            else if (Double.isNaN(want) ? Double.isNaN(got) : Math.abs(want - got) < 1e-9) sameTrends++;
        }
        check("#374 screen time per day equals the Screen tab's count, 60 days with an app left out, a trip and a gap", same == 60, same + " of 60 " + bad);
        check("#374 the days the log was not read are unknown, not zero (the first day and the gap)", unknown == 3, unknown);
        check("#374 Trends' screen time per day is the Screen tab's", sameTrends == 60, sameTrends);
        check("#374 the trip moved the days in New York (each day in its own zone)", !zones.get(first.plusDays(25)).equals(zh), zones.get(first.plusDays(25)));
        double[] all = ScreenDays.perDay(db, to.plusDays(1), to, out, now, app.openring.core.DeviceHistory.fixed(zh));
        check("#374 an empty range gives no days", all.length == 0, all.length);
        Context e = freshDb("trends-screen-none");
        double[] none = ScreenDays.perDay(Db.get(e), from, from.plusDays(2), out, now, app.openring.core.DeviceHistory.fixed(zh));
        check("#374 without any usage log every day is unknown", none.length == 3 && Double.isNaN(none[0]) && Double.isNaN(none[2]), none.length);
    }

    /** The demo's made up year before its five weeks (issue #374). */
    static void demoYear() throws Exception {
        Context c = freshDb("trends-demo");
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse("2026-10-25");
        long now = today.atTime(9, 41).atZone(z).toEpochSecond();
        long t0 = System.currentTimeMillis();
        Demo.enter(c, null, today, now);
        System.out.println("    #374 demo with its year built in " + (System.currentTimeMillis() - t0) + " ms");
        Db db = Db.get(c);
        LocalDate last = Demo.yearLast(today), first = last.minusDays(Demo.YEAR_DAYS - 1);
        List<String> yearNights = db.nightsJson(first.toString(), last.toString(), false);
        List<String[]> yearDays = db.daysJson(first.toString(), last.toString());
        check("#374 demo year: a main night and a day for each of the made up days", yearNights.size() == Demo.YEAR_DAYS && yearDays.size() == Demo.YEAR_DAYS,
                yearNights.size() + " " + yearDays.size());
        check("#374 demo year: nothing before it, and the gap of a day before the five weeks", db.nightsJson("2000-01-01", first.minusDays(1).toString(), false).isEmpty()
                && db.nightsJson(last.plusDays(1).toString(), last.plusDays(1).toString(), false).isEmpty(), null);
        check("#374 demo year: its last day noted for Re analyse all", last.toString().equals(db.kv(Demo.YEAR_UNTIL, null)), db.kv(Demo.YEAR_UNTIL, null));
        // an older night opens as a full night: stages, the series of the Sleep tab, its score, at its own date and clock times
        int full = 0, onDate = 0, clock = 0;
        for (String js : yearNights) {
            JSONObject o = Json.parse(js);
            LocalDate d = LocalDate.parse(o.optString("day"));
            if (Json.stages(o).length > 100 && o.optJSONArray("hr_series") != null && o.optJSONArray("hrv_series") != null
                    && o.optJSONArray("temp_series") != null && o.optJSONObject("score") != null && o.optBoolean("main")) full++;
            java.time.ZonedDateTime end = java.time.Instant.ofEpochSecond((long) Json.d(o, "sleep_end")).atZone(z);
            if (end.toLocalDate().equals(d)) onDate++;
            double bed = SleepClock.of(Json.d(o, "sleep_start"), z);
            if (bed >= 21 * 60 || bed <= 2 * 60) clock++;
            double t = o.optJSONArray("hr_series").optJSONArray(0).optDouble(0);
            if (Math.abs(t - Json.d(o, "start")) > 3600) clock -= 1000;
        }
        check("#374 demo year: every night with stages, the Sleep tab's series and its score", full == Demo.YEAR_DAYS, full);
        check("#374 demo year: every night ends on its own day", onDate == Demo.YEAR_DAYS, onDate);
        check("#374 demo year: bedtimes at the demo's clock times, across both clock changes, series moved with the night", clock == Demo.YEAR_DAYS, clock);
        // the story: about 25 minutes more sleep from four months ago, more steps in summer, one week of illness
        List<Trends.Day> days = TrendDays.read(db, today, now, Prefs.of(c).screenExcluded(), ShownNights.Devices.RING, z);
        LocalDate longer = today.minusMonths(Demo.LONGER_FROM_MONTHS);
        List<Double> before = new ArrayList<Double>(), after = new ArrayList<Double>(), summer = new ArrayList<Double>(), winter = new ArrayList<Double>();
        List<Double> illTemp = new ArrayList<Double>(), otherTemp = new ArrayList<Double>(), illRhr = new ArrayList<Double>(), otherRhr = new ArrayList<Double>();
        LocalDate ill = Demo.illFrom(today);
        for (Trends.Day d : days) {
            if (d.date.isAfter(last)) continue;
            (d.date.isBefore(longer) ? before : after).add(d.v[Trends.ASLEEP]);
            if (d.date.getMonthValue() >= 6 && d.date.getMonthValue() <= 8) summer.add(d.v[Trends.STEPS]);
            if (d.date.getMonthValue() == 12 || d.date.getMonthValue() <= 2) winter.add(d.v[Trends.STEPS]);
            boolean sick = !d.date.isBefore(ill) && d.date.isBefore(ill.plusDays(Demo.ILL_DAYS));
            (sick ? illTemp : otherTemp).add(d.v[Trends.TEMP]);
            (sick ? illRhr : otherRhr).add(d.v[Trends.RHR]);
        }
        double longerBy = Stats.mean(Stats.toArray(after)) - Stats.mean(Stats.toArray(before));
        double moreSteps = Stats.mean(Stats.toArray(summer)) / Stats.mean(Stats.toArray(winter));
        double warmer = Stats.mean(Stats.toArray(illTemp)) - Stats.mean(Stats.toArray(otherTemp));
        double faster = Stats.mean(Stats.toArray(illRhr)) - Stats.mean(Stats.toArray(otherRhr));
        System.out.printf(Locale.US, "    #374 demo year: %.0f min more sleep from %s, summer steps x%.2f, ill week %.2f degrees warmer, %.1f bpm%n",
                longerBy, longer, moreSteps, warmer, faster);
        check("#374 demo year: about 25 minutes more sleep from four months ago", longerBy > 15 && longerBy < 35, longerBy);
        check("#374 demo year: more steps in summer", moreSteps > 1.12, moreSteps);
        check("#374 demo year: the week of illness is warmer with a higher resting heart rate", warmer > 0.4 && faster >= 2, warmer + " " + faster);
        check("#374 demo: Trends has more than two months", Trends.enough(days, today), Trends.daysSoFar(days, today));
        Trends.View weeks = Trends.of(days, Trends.TEMP, Trends.WEEKS, today);
        int iw = weeks.indexOf(ill.plusDays(3));
        List<Double> weekly = new ArrayList<Double>();
        for (Trends.Bucket b : weeks.buckets) weekly.add(b.value);
        double usual = Stats.median(Stats.toArray(weekly));
        check("#374 demo: the week of illness stands out on the weekly temperature", iw >= 0 && weeks.buckets.get(iw).value > usual + 0.25,
                iw < 0 ? null : weeks.buckets.get(iw).value + " against " + usual);
        Trends.Compare cmp = Trends.compare(days, Trends.ASLEEP, today, Locale.US);
        System.out.println("    #374 demo: " + cmp.text);
        check("#374 demo: this month against the last has three weeks to compare on the 25th", cmp.enough, cmp.text);

        // the five weeks' own results are worked out from raw events; the year is not: Re analyse all keeps it byte for byte
        Map<String, String> keep = new HashMap<String, String>();
        for (String js : yearNights) keep.put("n" + Json.parse(js).optString("day"), js);
        for (String[] r : yearDays) keep.put("d" + r[0], r[1]);
        int fiveWeeks = db.nightsJson(last.plusDays(1).toString(), today.toString(), true).size();
        Analyzer.runAll(c, null);
        int kept = 0;
        for (String js : db.nightsJson(first.toString(), last.toString(), false)) if (js.equals(keep.get("n" + Json.parse(js).optString("day")))) kept++;
        for (String[] r : db.daysJson(first.toString(), last.toString())) if (r[1].equals(keep.get("d" + r[0]))) kept++;
        check("#374 demo: Re analyse all keeps the made up year byte for byte", kept == 2 * Demo.YEAR_DAYS, kept);
        check("#374 demo: Re analyse all still analyses the five weeks", db.nightsJson(last.plusDays(1).toString(), today.toString(), true).size() == fiveWeeks, fiveWeeks);

        // the all time fold: the months add up to Further analysis of all time (same nights, asleep, bedtime, wake ups)
        days = TrendDays.read(db, today, now, Prefs.of(c).screenExcluded(), ShownNights.Devices.RING, z);
        SleepHistory h = SleepHistory.load(db, "0000-01-01", today.toString(), z);
        Trends.View asleep = Trends.of(days, Trends.ASLEEP, Trends.MONTHS, today);
        int n = 0;
        double sum = 0;
        for (Trends.Bucket b : asleep.buckets) {
            n += b.n;
            if (b.n > 0) sum += b.value * b.n;
        }
        List<Double> bed = new ArrayList<Double>(), wakes = new ArrayList<Double>();
        for (Trends.Day d : days) {
            bed.add(d.v[Trends.BEDTIME]);
            wakes.add(d.v[Trends.WAKE_UPS]);
        }
        check("#374 demo: the months add up to all time (" + n + " nights)", n == h.nights, n + " " + h.nights);
        check("#374 demo: time asleep of the months equals Further analysis of all time", Math.abs(sum / n - h.asleepMin) < 0.01, sum / n + " " + h.asleepMin);
        check("#374 demo: bedtime equals Further analysis of all time", Math.abs(ClockAverage.of(Stats.toArray(bed))[0] - h.bedtimeMin) < 0.01,
                ClockAverage.of(Stats.toArray(bed))[0] + " " + h.bedtimeMin);
        check("#374 demo: wake ups equal Further analysis of all time", Math.abs(Stats.mean(Stats.toArray(wakes)) - h.wakeUps) < 1e-9, h.wakeUps);

        // screen time as the Screen tab counts it, every demo day (the made up maps app left out)
        int sameScreen = 0, screenDays = 0;
        for (Trends.Day d : days) {
            if (d.date.isAfter(today.minusDays(1)) || d.date.isBefore(today.minusDays(34))) continue;
            ScreenDays.Info in = ScreenDays.info(db, d.date, screenZone(db, d.date, z), 120, Prefs.of(c).screenExcluded(), 0, now);
            double want = in.day == null ? Double.NaN : in.day.totalMin;
            screenDays++;
            if (Double.isNaN(want) ? Double.isNaN(d.v[Trends.SCREEN]) : Math.abs(want - d.v[Trends.SCREEN]) < 1e-9) sameScreen++;
        }
        check("#374 demo: screen time per day equals the Screen tab on every demo day", sameScreen == screenDays && screenDays == 34, sameScreen + " of " + screenDays);

        // steps and active calories only on worn days: a day of the demo's with under an hour of ring data counts as unknown
        String wornDay = today.minusDays(3).toString();
        JSONObject dj = Json.parse(db.dayJson(wornDay));
        Json.put(dj, "covered_min", 30.0);
        db.putDay(wornDay, dj.toString());
        days = TrendDays.read(db, today, now, Prefs.of(c).screenExcluded(), ShownNights.Devices.RING, z);
        Trends.Day unworn = null;
        for (Trends.Day d : days) if (d.date.toString().equals(wornDay)) unworn = d;
        check("#374 a day without the ring for an hour has no steps or active calories, not zero", unworn != null && Double.isNaN(unworn.v[Trends.STEPS])
                && Double.isNaN(unworn.v[Trends.KCAL]) && !Double.isNaN(unworn.v[Trends.ASLEEP]), null);

        // only the watch (the demo shown as without a ring): its nights and days, no HRV or temperature, 5 weeks
        ShownNights.Devices watchOnly = new ShownNights.Devices(MainDevice.WATCH, false, true, MainDevice.WATCH, 8000, 40);
        List<Trends.Day> w = TrendDays.read(db, today, now, Prefs.of(c).screenExcluded(), watchOnly, z);
        int[] count = counts(w, Trends.WATCH);
        System.out.println("    #374 watch only: " + java.util.Arrays.toString(count));
        check("#374 watch only: sleep, bedtime, wake ups, sleep score, readiness, resting heart rate, steps, active calories from the watch",
                count[Trends.ASLEEP] > 25 && count[Trends.BEDTIME] > 25 && count[Trends.WAKE_UPS] > 25 && count[Trends.SLEEP_SCORE] > 25
                && count[Trends.READINESS] > 20 && count[Trends.RHR] > 20 && count[Trends.STEPS] > 25 && count[Trends.KCAL] > 25, java.util.Arrays.toString(count));
        check("#374 watch only: no HRV or temperature (a watch has none), nothing from the ring", count[Trends.HRV] == 0 && count[Trends.TEMP] == 0
                && counts(w, Trends.RING)[Trends.ASLEEP] == 0, null);
        check("#374 watch only: under two months, so Trends says how many days there are", !Trends.enough(w, today) && Trends.daysSoFar(w, today) >= 35
                && Trends.daysSoFar(w, today) <= 37, Trends.daysSoFar(w, today));
        // only the phone: its steps and the screen time
        ShownNights.Devices phoneOnly = new ShownNights.Devices(MainDevice.PHONE, false, false, MainDevice.PHONE, 8000, 40);
        List<Trends.Day> p = TrendDays.read(db, today, now, Prefs.of(c).screenExcluded(), phoneOnly, z);
        int[] pc = counts(p, Trends.PHONE);
        System.out.println("    #374 phone only: " + java.util.Arrays.toString(pc));
        check("#374 phone only: steps and screen time from the phone", pc[Trends.STEPS] > 25 && pc[Trends.SCREEN] > 25, java.util.Arrays.toString(pc));
        int[] pr = counts(p, Trends.RING), pw = counts(p, Trends.WATCH);
        check("#374 phone only: no sleep, heart or ring values", pr[Trends.ASLEEP] + pw[Trends.ASLEEP] + pr[Trends.HRV] + pr[Trends.RHR] + pw[Trends.RHR] + pc[Trends.ASLEEP] == 0, null);
        // ring and watch, the watch as the main device: its nights where it has one, the ring's otherwise (ShownNights' rule)
        ShownNights.Devices both = new ShownNights.Devices(MainDevice.WATCH, true, true, -1, 8000, 40);
        List<Trends.Day> bw = TrendDays.read(db, today, now, Prefs.of(c).screenExcluded(), both, z);
        int[] bwW = counts(bw, Trends.WATCH), bwR = counts(bw, Trends.RING);
        check("#374 ring and watch, the watch main: the watch's nights in the five weeks, the ring's in the year", bwW[Trends.ASLEEP] > 25
                && bwR[Trends.ASLEEP] >= Demo.YEAR_DAYS, bwW[Trends.ASLEEP] + " " + bwR[Trends.ASLEEP]);
        Trends.View mixed = Trends.of(bw, Trends.ASLEEP, Trends.MONTHS, today);
        String ro = Trends.readout(mixed, mixed.indexOf(last), Locale.US);
        System.out.println("    #374 " + ro);
        check("#374 the readout of a month with ring and watch nights names both", ro.contains(" ring, ") && ro.endsWith(" watch"), ro);
        Demo.leave(c);
    }

    /** The events of one ring boot, as PipelineTest stores them. */
    static void putBoot(Db db, List<RingEvent> ev, int epoch, long t0, long end) {
        for (int i = 0; i < ev.size(); i += 4000) db.insertBatch(ev.subList(i, Math.min(ev.size(), i + 4000)), epoch, end);
        long lastDs = ev.get(ev.size() - 1).ds;
        db.insertPhoneAnchor(epoch, lastDs, t0 + (lastDs - Demo.DS0) / 10);
    }

    /** How many days have each measure from {@code src}. */
    static int[] counts(List<Trends.Day> days, int src) {
        int[] out = new int[Trends.COUNT];
        for (Trends.Day d : days) for (int m = 0; m < Trends.COUNT; m++) if (!Double.isNaN(d.v[m]) && d.src[m] == src) out[m]++;
        return out;
    }

    /** Clock minutes of a moment, as Further analysis reads a night's times. */
    static final class SleepClock {
        static double of(double unix, ZoneId z) {
            java.time.ZonedDateTime t = java.time.Instant.ofEpochSecond((long) unix).atZone(z);
            return t.getHour() * 60 + t.getMinute() + t.getSecond() / 60.0;
        }
    }

    /**
     * Nights count in the period of their stored day: across the clock change (the demo of 12 April 2026) bedtimes stay at their
     * clock times and each night is in the week and month of its day; on a trip (10 nights in Zurich, 5 in New York) each night
     * keeps its own clock.
     */
    static void clockChangeAndTrip() throws Exception {
        Context c = freshDb("trends-spring");
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse("2026-04-12");
        long now = today.atTime(12, 0).atZone(z).toEpochSecond();
        Demo.enter(c, null, today, now);
        Db db = Db.get(c);
        List<Trends.Day> days = TrendDays.read(db, today, now, Prefs.of(c).screenExcluded(), ShownNights.Devices.RING, z);
        int filed = 0, nights = 0;
        for (String js : db.nightsJson("2000-01-01", today.toString(), true)) {
            JSONObject o = Json.parse(js);
            LocalDate d = LocalDate.parse(o.optString("day"));
            nights++;
            for (Trends.Day x : days) {
                if (!x.date.equals(d)) continue;
                double want = SleepClock.of(Json.d(o, "sleep_start"), Json.zone(o, z));
                if (Math.abs(x.v[Trends.BEDTIME] - want) < 1e-6) filed++;
            }
        }
        check("#374 every night is filed by its stored day with its own clock time (demo over the spring clock change)", filed == nights, filed + " of " + nights);
        Trends.View w = Trends.of(days, Trends.BEDTIME, Trends.WEEKS, today);
        int before = w.indexOf(LocalDate.parse("2026-03-22")), after = w.indexOf(LocalDate.parse("2026-04-05"));
        double jump = Math.abs(Trends.around(w.buckets.get(after).value, w.buckets.get(before).value));
        check("#374 bedtime keeps its clock time across the clock change (weeks before and after within 40 min)", jump < 40, jump);
        check("#374 Sunday 29 March (the clock change) is in the week of Monday 23 March", w.buckets.get(w.indexOf(LocalDate.parse("2026-03-29"))).start
                .equals(LocalDate.parse("2026-03-23")), null);
        Demo.leave(c);

        // a trip: the nights in New York are filed by their own day with New York clock times
        ZoneId zh = ZoneId.of("Europe/Zurich"), ny = ZoneId.of("America/New_York");
        long t0 = LocalDate.parse("2026-09-01").atStartOfDay(zh).toEpochSecond(), end0 = t0 + 11 * 86400L + 12 * 3600;
        long t1 = LocalDate.parse("2026-09-13").atStartOfDay(ny).toEpochSecond(), end1 = t1 + 6 * 86400L + 12 * 3600;
        Context tc = freshDb("trends-trip");
        Db tdb = Db.get(tc);
        for (int d = 2; d <= 12; d++) tdb.noteZone(LocalDate.parse("2026-09-01").plusDays(d - 1).atTime(8, 0).atZone(zh).toEpochSecond(), "Europe/Zurich");
        for (int d = 14; d <= 19; d++) tdb.noteZone(LocalDate.parse("2026-09-01").plusDays(d - 1).atTime(8, 0).atZone(ny).toEpochSecond(), "America/New_York");
        putBoot(tdb, Demo.history(t0, 10, end0, zh, new Random(21)), 1, t0, end0);
        putBoot(tdb, Demo.history(t1, 5, end1, ny, new Random(22)), 2, t1, end1);
        Analyzer.runAll(tc, null);
        LocalDate tday = LocalDate.parse("2026-09-19");
        List<Trends.Day> trip = TrendDays.read(tdb, tday, end1, new TreeSet<String>(), ShownNights.Devices.RING, zh);
        int ok = 0, all = 0, inNy = 0;
        for (String js : tdb.nightsJson("2000-01-01", tday.toString(), true)) {
            JSONObject o = Json.parse(js);
            LocalDate d = LocalDate.parse(o.optString("day"));
            all++;
            if (ny.getId().equals(o.optString("zone"))) inNy++;
            for (Trends.Day x : trip) {
                if (x.date.equals(d) && Math.abs(x.v[Trends.BEDTIME] - SleepClock.of(Json.d(o, "sleep_start"), Json.zone(o, zh))) < 1e-6) ok++;
            }
        }
        check("#374 trip: every night in its own day with the clock of the zone it was slept in", ok == all && inNy >= 4, ok + " of " + all + ", " + inNy + " in New York");
        // as Android stores them: org.json there writes the zones' slashes as \/ (code review of #413); the same bedtimes
        tdb.getWritableDatabase().execSQL("UPDATE nights SET json = replace(replace(json, 'America/New_York', 'America\\/New_York'), 'Europe/Zurich', 'Europe\\/Zurich')");
        long escaped = tdb.count("SELECT COUNT(*) FROM nights WHERE json LIKE '%America\\/New_York%'");
        List<Trends.Day> again = TrendDays.read(tdb, tday, end1, new TreeSet<String>(), ShownNights.Devices.RING, zh);
        int sameBed = 0, cmp = 0;
        for (int i = 0; i < trip.size(); i++) {
            double a = trip.get(i).v[Trends.BEDTIME], b = again.get(i).v[Trends.BEDTIME];
            if (Double.isNaN(a)) continue;
            cmp++;
            if (a == b) sameBed++;
        }
        check("#374 trip with zones stored as \"America\\/New_York\": the same bedtimes in New York clock time", escaped >= 4 && sameBed == cmp && cmp == all,
                escaped + " escaped, " + sameBed + " of " + cmp);
        Trends.View tw = Trends.of(trip, Trends.BEDTIME, Trends.MONTHS, tday);
        double[] r = tw.range();
        check("#374 trip: bedtimes in New York stay evening times, the band no wider than a few hours", r[1] - r[0] < 4 * 60, r[0] + " " + r[1]);
    }

    /**
     * Data safety (code review of #413): Re analyse all of the person's own database deletes and rebuilds every night and day,
     * also with a kv row demo_year_until in it (only the demo file keeps its made up year).
     */
    static void realReanalyse() throws Exception {
        ZoneId z = ZoneId.of("Europe/Zurich");
        long t0 = LocalDate.parse("2026-09-01").atStartOfDay(z).toEpochSecond(), end = t0 + 11 * 86400L + 12 * 3600;
        Context c = freshDb("trends-real");
        Db db = Db.get(c);
        check("#374 the person's own file", Db.REAL.equals(db.fileName), db.fileName);
        putBoot(db, Demo.history(t0, 10, end, z, new Random(21)), 1, t0, end);
        Analyzer.runAll(c, null);
        List<String> nights = db.nightsJson("2000-01-01", "2100-01-01", false);
        List<String[]> days = db.daysJson("2000-01-01", "2100-01-01");
        // a stray night and day before the events, and the demo's key in the kv table
        List<Object[]> stray = new ArrayList<Object[]>();
        double s = LocalDate.parse("2026-07-01").atTime(23, 0).atZone(z).toEpochSecond();
        stray.add(new Object[] {s, s + 7 * 3600, "2026-07-02", 1, 80, "{\"day\":\"2026-07-02\",\"main\":true,\"start\":" + s + "}"});
        db.replaceNights(s - 1, s + 1, stray);
        db.putDay("2026-07-02", "{\"date\":\"2026-07-02\",\"steps\":1000}");
        db.put(Demo.YEAR_UNTIL, "2026-08-30");
        Analyzer.runAll(c, null);
        List<String> nights2 = db.nightsJson("2000-01-01", "2100-01-01", false);
        List<String[]> days2 = db.daysJson("2000-01-01", "2100-01-01");
        boolean sameDays = days.size() == days2.size();
        for (int i = 0; sameDays && i < days.size(); i++) sameDays = days.get(i)[0].equals(days2.get(i)[0]) && days.get(i)[1].equals(days2.get(i)[1]);
        check("#374 data safety: Re analyse all of the own file deletes everything stored, also with demo_year_until in its kv",
                db.nightsJson("2026-07-02", "2026-07-02", false).isEmpty() && db.dayJson("2026-07-02") == null, null);
        check("#374 data safety: and rebuilds the same nights and days as before", nights.equals(nights2) && sameDays, nights.size() + " " + nights2.size());
    }

    /** Trends reads the history again only when its stamp changed: the same database gives the same stamp, a new row another. */
    static void stamp() throws Exception {
        Context c = freshDb("trends-stamp");
        Db db = Db.get(c);
        String a = TrendDays.stamp(db), b = TrendDays.stamp(db);
        db.putDay("2026-07-02", "{\"date\":\"2026-07-02\",\"steps\":1000}");
        String d = TrendDays.stamp(db);
        List<double[]> s = new ArrayList<double[]>();
        s.add(new double[] {1.8e9, 1.8e9 + 60});
        db.putScreen(s);
        String e = TrendDays.stamp(db);
        db.put("last_analysis", 12345);
        String f = TrendDays.stamp(db);
        System.out.println("    #374 stamp " + f);
        check("#374 stamp: unchanged without a change, new after a day, a screen session and an analysis", a.equals(b) && !a.equals(d) && !d.equals(e) && !e.equals(f), a + " / " + f);
    }

    /** Three years of nights and days (copies of the demo's) and of screen sessions: read and grouped under 1.5 s. */
    static void threeYears() throws Exception {
        Context src = freshDb("trends-3y-src");
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse("2026-11-18");
        long now = today.atTime(9, 41).atZone(z).toEpochSecond();
        Demo.enter(src, null, today, now);
        Db sdb = Db.get(src);
        List<String[]> pairs = new ArrayList<String[]>();
        for (String[] r : sdb.daysJson(today.minusDays(30).toString(), today.minusDays(2).toString())) {
            List<String> n = sdb.nightsJson(r[0], r[0], true);
            if (n.size() == 1) pairs.add(new String[] {r[0], n.get(0), r[1]});
        }
        Demo.leave(src);
        Context c = freshDb("trends-3y");
        final Db db = Db.get(c);
        final List<Object[]> nights = new ArrayList<Object[]>();
        final List<String[]> days = new ArrayList<String[]>();
        List<double[]> screen = new ArrayList<double[]>();
        Random r = new Random(3);
        LocalDate first = today.minusDays(3 * 365);
        for (LocalDate d = first; d.isBefore(today); d = d.plusDays(1)) {
            String[] p = pairs.get(r.nextInt(pairs.size()));
            LocalDate from = LocalDate.parse(p[0]);
            JSONObject n = Demo.moved(Json.parse(p[1]), from, d, z), dj = Demo.moved(Json.parse(p[2]), from, d, z);
            nights.add(new Object[] {Json.d(n, "start"), Json.d(n, "end"), d.toString(), 1, 80, n.toString()});
            days.add(new String[] {d.toString(), dj.toString()});
            for (int k = 0; k < 30; k++) {
                double a = d.atStartOfDay(z).toEpochSecond() + 7 * 3600 + r.nextInt(15 * 3600);
                screen.add(new double[] {a, a + 20 + r.nextInt(600)});
            }
        }
        db.inTransaction(new Runnable() {
            @Override
            public void run() {
                db.replaceNights(0, Double.MAX_VALUE, nights);
                for (String[] x : days) db.putDay(x[0], x[1]);
            }
        });
        db.putScreen(screen);
        db.putScreenRead(first.atStartOfDay(z).toEpochSecond(), now);
        // a first read warms the code up as a phone that opened the app has it; the second is timed
        TrendDays.read(db, today, now, new TreeSet<String>(), ShownNights.Devices.RING, z);
        long t0 = System.nanoTime();
        List<Trends.Day> td = TrendDays.read(db, today, now, new TreeSet<String>(), ShownNights.Devices.RING, z);
        long t1 = System.nanoTime();
        int periods = 0;
        for (int m = 0; m < Trends.COUNT; m++) {
            for (int p = 0; p < 3; p++) periods += Trends.of(td, m, p, today).buckets.size();
            Trends.compare(td, m, today, Locale.US);
        }
        double readMs = (t1 - t0) / 1e6, allMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US, "    #374 three years (%d days, %d screen sessions): read in %.0f ms, every measure and period grouped in %.0f ms in all%n",
                td.size(), screen.size(), readMs, allMs);
        int asleep = 0, scr = 0;
        for (Trends.Day d : td) {
            if (!Double.isNaN(d.v[Trends.ASLEEP])) asleep++;
            if (!Double.isNaN(d.v[Trends.SCREEN])) scr++;
        }
        check("#374 three years: every night and every day of screen time read", asleep == 3 * 365 && scr >= 3 * 365, asleep + " " + scr);
        check("#374 three years of nights read and grouped under 1.5 s", allMs < 1500, Math.round(allMs) + " ms");
        check("#374 three years: " + periods + " periods", periods > 13 * (156 + 36 + 3), periods);
    }

    /** Periods.ringNights before the day summaries were read field by field (code health): the reference, kept word for word. */
    static java.util.TreeMap<LocalDate, Double> oldRingNights(Db db, LocalDate from, LocalDate to, ZoneId zone) {
        android.database.sqlite.SQLiteDatabase d = db.getReadableDatabase();
        List<double[]> rows = new ArrayList<double[]>();
        android.database.Cursor c = d.rawQuery("SELECT start, stop FROM excluded", null);
        try {
            while (c.moveToNext()) rows.add(new double[] {c.getDouble(0), c.getDouble(1)});
        } finally {
            c.close();
        }
        app.openring.core.analysis.LeftOut left = app.openring.core.analysis.LeftOut.of(rows);
        java.util.TreeMap<LocalDate, Double> out = new java.util.TreeMap<LocalDate, Double>();
        c = d.rawQuery("SELECT day, json FROM days WHERE day >= ? AND day <= ? ORDER BY day", new String[] {from.toString(), to.toString()});
        try {
            while (c.moveToNext()) {
                JSONObject o = Json.parse(c.getString(1));
                double t = app.openring.core.analysis.TempRise.ringNight(Json.d(o, "temp"), Json.d(o, "asleep_min"));
                if (Double.isNaN(t)) continue;
                LocalDate day = LocalDate.parse(c.getString(0));
                if (!left.isEmpty() && left.overlaps(day.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond(), day.atTime(12, 0).atZone(zone).toEpochSecond())) continue;
                out.put(day, t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Code health: the period tracker's ring nights read only temp and asleep_min from each day summary instead of parsing all
     * of it. The same nights and values as the old loop, on rows of every odd shape and on three years of the demo's day
     * summaries (heart rate of the day and burn profile included), with a night someone else wore the ring.
     */
    static void periodRingNights() throws Exception {
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.parse("2026-11-18");
        Context src = freshDb("rings-src");
        Demo.enter(src, null, today, today.atTime(9, 41).atZone(z).toEpochSecond());
        Db sdb = Db.get(src);
        List<String[]> demoDays = new ArrayList<String[]>();
        for (String[] r : sdb.daysJson(today.minusDays(30).toString(), today.minusDays(2).toString())) {
            if (!Double.isNaN(Json.d(Json.parse(r[1]), "temp"))) demoDays.add(r);
        }
        Demo.leave(src);

        // rows of every shape, one per day
        Context c1 = freshDb("rings-odd");
        Db db = Db.get(c1);
        String[] odd = {
            "{\"temp\":35.21,\"asleep_min\":430}",
            "{\"asleep_min\":430}",
            "{\"temp\":null,\"asleep_min\":430}",
            "{\"temp\":35.3,\"asleep_min\":95}",
            "{\"temp\":35.31}",
            "{\"temp\":35.32,\"asleep_min\":null}",
            "{\"temp\":35.2,\"asleep_min\":4",
            "not json",
            "",
            "{\"temp\":\"35.4\",\"asleep_min\":430}",
            "{\"temp\":35.33,\"asleep_min\":\"100\"}",
            "{\"temp\":3.534e1,\"asleep_min\":4.3E2}",
            "{\"temp\":-0.5,\"asleep_min\":0}",
            "{\"temp\":35,\"asleep_min\":180}",
            "{\"temp\":35.35,\"asleep_min\":179.99}",
            "{\"temp\":{\"a\":1},\"asleep_min\":430}",
            "{\"temp\":[35.2],\"asleep_min\":430}",
            "{\"temp\":true,\"asleep_min\":430}",
            " { \"temp\" : 35.36 , \"asleep_min\" : 430 } ",
            "{\"radar\":{\"temp\":99},\"asleep_min\":430}",
            "{\"notes\":\"a \\\"temp\\\":1, b\",\"temp\":35.37}",
            "{\"hr_day\":[[1,60],[2,61]],\"temp\":35.38,\"met_profile\":[1.1,1.2],\"asleep_min\":400}",
            "{\"temp\":35.39,\"asleep_min\":400,}",
            "{}",
            "[35.2]",
            // numbers JSON does not write this way take the old way too (sign alone, plus, leading zero, hex, cut short)
            "{\"temp\":-,\"asleep_min\":430}",
            "{\"temp\":+35.2,\"asleep_min\":430}",
            "{\"temp\":035.2,\"asleep_min\":430}",
            "{\"temp\":0x23,\"asleep_min\":430}",
            "{\"temp\":35.,\"asleep_min\":430}",
            "{\"temp\":3.5e,\"asleep_min\":430}",
            "{\"temp\":3.5e+,\"asleep_min\":430}",
            "{\"temp\":35.2.1,\"asleep_min\":430}",
            "{\"temp\":NaN,\"asleep_min\":430}",
            "{\"temp\":35.41,\"asleep_min\":-0}",
            "{\"temp\":0.5,\"asleep_min\":1e+3}",
            "{\"temp\":0,\"asleep_min\":4.3e-0}",
            "{\"temp\":-35.42,\"asleep_min\":43E1}",
        };
        LocalDate first = today.minusDays(odd.length - 1);
        for (int i = 0; i < odd.length; i++) db.putDay(first.plusDays(i).toString(), odd[i]);
        java.util.TreeMap<LocalDate, Double> was = oldRingNights(db, first, today, z), now = Periods.ringNights(db, first, today, z);
        check("ring nights: every odd day summary gives what it gave before", was.equals(now), was.size() + " nights before, " + now.size() + " now");
        check("ring nights: the odd rows still give some nights and leave others out", was.size() >= 8 && was.size() < odd.length, was.keySet());

        // three years of the demo's real sized day summaries, 1 in 10 nights missing, a short night, a night left out
        Context c2 = freshDb("rings-3y");
        final Db big = Db.get(c2);
        final List<String[]> rows = new ArrayList<String[]>();
        Random r = new Random(11);
        final LocalDate from = today.minusDays(3 * 365);
        long bytes = 0;
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            if (r.nextDouble() < 0.1) continue;
            String[] p = demoDays.get(r.nextInt(demoDays.size()));
            JSONObject o = Demo.moved(Json.parse(p[1]), LocalDate.parse(p[0]), d, z);
            if (r.nextDouble() < 0.03) o.put("asleep_min", 95);
            rows.add(new String[] {d.toString(), o.toString()});
            bytes += o.toString().length();
        }
        big.inTransaction(new Runnable() {
            @Override
            public void run() {
                for (String[] x : rows) big.putDay(x[0], x[1]);
            }
        });
        LocalDate left = today.minusDays(200);
        big.addExcluded(left.minusDays(1).atTime(23, 0).atZone(z).toEpochSecond(), left.atTime(3, 0).atZone(z).toEpochSecond(), 9100);
        java.util.TreeMap<LocalDate, Double> a = null, b = null;
        double oldMs = Double.MAX_VALUE, newMs = Double.MAX_VALUE;
        for (int k = 0; k < 4; k++) { // the first round warms the code up; the best of the other three counts
            long t0 = System.nanoTime();
            a = oldRingNights(big, from, today, z);
            long t1 = System.nanoTime();
            b = Periods.ringNights(big, from, today, z);
            long t2 = System.nanoTime();
            if (k > 0) {
                oldMs = Math.min(oldMs, (t1 - t0) / 1e6);
                newMs = Math.min(newMs, (t2 - t1) / 1e6);
            }
        }
        System.out.printf(Locale.US, "    ring nights, three years (%d day summaries, %d KB on average each): %.0f ms before, %.0f ms now%n",
                rows.size(), bytes / rows.size() / 1024, oldMs, newMs);
        check("ring nights: three years of day summaries give the same nights and temperatures as before", a.equals(b) && a.size() > 900, a.size() + " and " + b.size());
        check("ring nights: the night someone else wore the ring stays out", !b.containsKey(left), left);
        check("ring nights: every temperature bit for bit as before", sameBits(a, b), null);
    }

    static boolean sameBits(java.util.TreeMap<LocalDate, Double> a, java.util.TreeMap<LocalDate, Double> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<LocalDate, Double> e : a.entrySet()) {
            if (Double.doubleToLongBits(e.getValue()) != Double.doubleToLongBits(b.get(e.getKey()))) return false;
        }
        return true;
    }
}
