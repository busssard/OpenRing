package app.openring.data;

import java.time.LocalDate;
import java.time.ZoneId;

import app.openring.core.analysis.TS;

/**
 * One value per day as a time series for the line charts with one dot per day ({@code LineChart.daily}): each value at noon of
 * its day, days without a value left out. The ring's 30 day charts ({@code Pages.daysSeries}) and the charts without a ring
 * ({@code HistoryViews.series}) had the same code each; both use this one now (issue #374).
 */
public final class DaySeries {
    private DaySeries() {}

    /** The values of the {@code v.length} days ending at {@code end}, oldest first, each at noon of its day in {@code zone}. */
    public static TS of(LocalDate end, double[] v, ZoneId zone) {
        TS ts = new TS();
        for (int i = 0; i < v.length; i++) {
            if (Double.isNaN(v[i])) continue;
            ts.add(end.minusDays(v.length - 1 - i).atStartOfDay(zone).toEpochSecond() + 43200, v[i]);
        }
        return ts;
    }

    /** As above in the phone's zone, as the charts draw them. */
    public static TS of(LocalDate end, double[] v) {
        return of(end, v, ZoneId.systemDefault());
    }
}
