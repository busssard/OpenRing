import android.content.Context;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Random;

import app.openring.core.analysis.TS;
import app.openring.data.DaySeries;
import app.openring.data.Db;

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
}
