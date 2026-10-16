package app.openring.core.analysis;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;

/**
 * Where the clocks changed during a night (daylight saving, issue #22). Times inside a night are
 * real seconds, so durations are right anyway; this only tells the screens to mark the moment.
 */
public final class ClockChange {
    private ClockChange() {}

    /** The moment (unix seconds) and the change in minutes: +60 when the clocks go forward, -60 when they go back. */
    public static final class Change {
        public final double at;
        public final int minutes;

        Change(double at, int minutes) {
            this.at = at;
            this.minutes = minutes;
        }
    }

    /** The first change of {@code zone}'s offset in [start, end), or null. */
    public static Change find(ZoneId zone, double start, double end) {
        if (zone == null || Double.isNaN(start) || Double.isNaN(end) || end <= start) return null;
        ZoneOffsetTransition t = zone.getRules().nextTransition(Instant.ofEpochSecond((long) Math.ceil(start) - 1)); // strictly after its argument
        if (t == null) return null;
        long at = t.getInstant().getEpochSecond();
        if (at < start || at >= end) return null;
        int min = (t.getOffsetAfter().getTotalSeconds() - t.getOffsetBefore().getTotalSeconds()) / 60;
        return min == 0 ? null : new Change(at, min);
    }

    /**
     * The sentence under the sleep chart, for example "The clocks went back one hour this night
     * (03:00 became 02:00). ...". {@code after} is the local clock time just after the change.
     */
    public static String text(int minutes, LocalTime after) {
        int m = Math.abs(minutes);
        String amount = m == 60 ? "one hour" : m % 60 == 0 ? (m / 60) + " hours" : m + " minutes";
        LocalTime before = after.minusMinutes(minutes);
        String clocks = "(" + hm(before) + " became " + hm(after) + ")";
        if (minutes < 0) {
            return "The clocks went back " + amount + " this night " + clocks + ". Sleep times count the real hours, so you had "
                    + amount + " more in bed than the clock shows.";
        }
        return "The clocks went forward " + amount + " this night " + clocks + ". Sleep times count the real hours, so the night was "
                + amount + " shorter than the clock shows.";
    }

    static String hm(LocalTime t) {
        return String.format(java.util.Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
    }
}
