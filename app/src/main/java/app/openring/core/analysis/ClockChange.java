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

    /**
     * The moment (unix seconds), the change in minutes (+60 when the clocks go forward, -60 when
     * they go back) and the local clock just before and after it ("03:00" and "02:00").
     */
    public static final class Change {
        public final double at;
        public final int minutes;
        public final String from, to;

        Change(double at, int minutes, String from, String to) {
            this.at = at;
            this.minutes = minutes;
            this.from = from;
            this.to = to;
        }
    }

    /** The first change of {@code zone}'s offset in [start, end), or null. */
    public static Change find(ZoneId zone, double start, double end) {
        if (zone == null || Double.isNaN(start) || Double.isNaN(end) || end <= start) return null;
        // nextTransition is strictly after its argument; a change of 0 minutes (java.time refuses them
        // today) would be passed over
        ZoneOffsetTransition t = zone.getRules().nextTransition(Instant.ofEpochSecond((long) Math.ceil(start) - 1));
        while (t != null && t.getInstant().getEpochSecond() < end) {
            long at = t.getInstant().getEpochSecond();
            int min = (t.getOffsetAfter().getTotalSeconds() - t.getOffsetBefore().getTotalSeconds()) / 60;
            if (min != 0) return new Change(at, min, hm(t.getDateTimeBefore().toLocalTime()), hm(t.getDateTimeAfter().toLocalTime()));
            t = zone.getRules().nextTransition(t.getInstant());
        }
        return null;
    }

    /**
     * The sentence under the sleep chart, for example "The clocks went back one hour that night
     * (03:00 became 02:00). ...". {@code from} and {@code to} are the local clock just before and
     * after the change, as stored with the night; when they are missing the brackets are left out.
     */
    public static String text(int minutes, String from, String to) {
        int m = Math.abs(minutes);
        String amount = m == 60 ? "one hour" : m % 60 == 0 ? (m / 60) + " hours" : m + " minutes";
        String clocks = from == null || to == null || from.isEmpty() || to.isEmpty() ? "" : " (" + from + " became " + to + ")";
        if (minutes < 0) {
            return "The clocks went back " + amount + " that night" + clocks + ". Sleep times count the real hours, so you had "
                    + amount + " more in bed than the clock shows.";
        }
        return "The clocks went forward " + amount + " that night" + clocks + ". Sleep times count the real hours, so the night was "
                + amount + " shorter than the clock shows.";
    }

    static String hm(LocalTime t) {
        return String.format(java.util.Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
    }
}
