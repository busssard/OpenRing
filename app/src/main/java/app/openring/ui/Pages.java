package app.openring.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.util.List;

import app.openring.core.analysis.TS;

/**
 * The six main screens, built from a {@link DayData}: one class per tab ({@link TodayPage}, {@link SleepPage},
 * {@link ReadinessPage}, {@link ActivityPage}, {@link BodyPage}, {@link ScreenPage}) and the parts they share
 * ({@link PageParts}), issue #293 phase 0. This class is the entry: the navigation, the tab numbers and one line per tab.
 */
public final class Pages {
    private Pages() {}

    public interface Nav {
        void go(int tab);

        /** Open Live; {@code spo2} with blood oxygen switched on. Demo mode shows made up readings there (issue #185). */
        void live(boolean spo2);

        void sync();

        /** Show another day (e.g. a night picked from the recent nights strip). */
        void showDay(java.time.LocalDate day);

        /** Load the day again (after a setting on the page changed). */
        void reload();
    }

    // core/Tour.TODAY and SLEEP use these numbers (issue #453, TourTest checks 0 and 1): change them together
    public static final int TODAY = 0, SLEEP = 1, READINESS = 2, ACTIVITY = 3, BODY = 4, SCREEN = 5;

    public static View today(final Context c, DayData d, final Nav nav, String syncLine) {
        return TodayPage.view(c, d, nav, syncLine);
    }

    public static View sleep(Context c, DayData d, Nav nav) {
        return SleepPage.view(c, d, nav);
    }

    public static View readiness(Context c, DayData d, Nav nav) {
        return ReadinessPage.view(c, d, nav);
    }

    public static View activity(Context c, DayData d) {
        return ActivityPage.view(c, d);
    }

    public static View body(final Context c, DayData d, final Nav nav) {
        return BodyPage.view(c, d, nav);
    }

    public static View screen(final Context c, DayData d, final Nav nav) {
        return ScreenPage.view(c, d, nav);
    }

    // ---- the parts other screens use (MainActivity, DeviceViews, HandViews, HistoryViews, Charts), under the names they had
    // before the split; new code calls the tab's class or PageParts directly

    static final String DAY_LINE_TAG = TodayPage.DAY_LINE_TAG;
    static final boolean[] dayWhyOpen = TodayPage.dayWhyOpen;
    static final String ANALYSIS_KEY = SleepPage.ANALYSIS_KEY;
    static final String TOTAL_SLEEP_NOTE = SleepPage.TOTAL_SLEEP_NOTE;

    static View ringButton(Context c, double v, String label, int color, final Nav nav, final int tab) {
        return TodayPage.ringButton(c, v, label, color, nav, tab);
    }

    static View ringButton(Context c, double v, String label, int color, final Nav nav, final int tab, String note) {
        return TodayPage.ringButton(c, v, label, color, nav, tab, note);
    }

    static View recentNights(Context c, DayData d, List<JSONObject> nights, final Nav nav) {
        return SleepPage.recentNights(c, d, nights, nav);
    }

    static View furtherAnalysis(final Context c, final DayData d) {
        return SleepPage.furtherAnalysis(c, d);
    }

    static void addNaps(Context c, LinearLayout root, DayData d) {
        SleepPage.addNaps(c, root, d);
    }

    static View signalRow(Context c, String name, double value, double usual, String unit, int dp, boolean lowerIsBetter) {
        return ReadinessPage.signalRow(c, name, value, usual, unit, dp, lowerIsBetter);
    }

    static boolean phoneCounted(DayData d) {
        return ActivityPage.phoneCounted(d);
    }

    static boolean screenReady(Context c) {
        return ScreenPage.screenReady(c);
    }

    static String span(double min) {
        return ScreenPage.span(min);
    }

    static String stepsLine(DayData d) {
        return PageParts.stepsLine(d);
    }

    static View leftOutCard(Context c, DayData d) {
        return PageParts.leftOutCard(c, d);
    }

    static String minute(double unix, java.time.ZoneId z) {
        return PageParts.minute(unix, z);
    }

    static View infoCard(Context c, String title, String text) {
        return PageParts.infoCard(c, title, text);
    }

    static TS daysSeries(DayData d, String field, int n) {
        return PageParts.daysSeries(d, field, n);
    }

    static View hero(Context c, double score, String label, int color, String sub) {
        return PageParts.hero(c, score, label, color, sub);
    }

    static View hero(Context c, double score, String label, int color, String sub, String note) {
        return PageParts.hero(c, score, label, color, sub, note);
    }

    static View contributors(Context c, JSONObject score, int color, String title) {
        return PageParts.contributors(c, score, color, title);
    }

    static View contributors(Context c, JSONObject score, int color, String title, JSONObject day, JSONObject night) {
        return PageParts.contributors(c, score, color, title, day, night);
    }

    static View chartCard(Context c, String title, View chart, String foot) {
        return PageParts.chartCard(c, title, chart, foot);
    }
}
