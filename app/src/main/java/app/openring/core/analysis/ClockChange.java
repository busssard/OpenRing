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

    /**
     * The line under the sleep chart after travelling (issue #22), for example "Times this night are
     * in New York time, 6 hours behind where you slept the night before.", or null when both zones have the same
     * clock at {@code at} (Zurich and Berlin), or an id is missing or unknown.
     */
    public static String travel(String prevId, String id, double at) {
        ZoneId prev = zone(prevId), now = zone(id);
        if (prev == null || now == null || prev.equals(now) || Double.isNaN(at)) return null;
        Instant t = Instant.ofEpochSecond((long) at);
        int diff = (now.getRules().getOffset(t).getTotalSeconds() - prev.getRules().getOffset(t).getTotalSeconds()) / 60;
        if (diff == 0) return null;
        return "Times this night are in " + zoneName(id) + ", " + amount(Math.abs(diff)) + (diff > 0 ? " ahead of" : " behind")
                + " where you slept the night before.";
    }

    /**
     * The line under the screen time clock on a day the clocks changed (issue #46), for example "The
     * clocks went back one hour on this day (03:00 became 02:00). ...", or null without a change.
     */
    public static String dialText(Change c) {
        if (c == null || c.minutes == 0) return null;
        String amount = amount(Math.abs(c.minutes));
        if (c.minutes < 0) {
            return "The clocks went back " + amount + " on this day (" + c.from + " became " + c.to + "). The hour from " + c.to
                    + " to " + c.from + " happened twice, phone use in both shows on the same part of the clock, marked in orange.";
        }
        return "The clocks went forward " + amount + " on this day (" + c.from + " became " + c.to + "), so the part of the clock from "
                + c.from + " to " + c.to + ", marked in orange, stays empty.";
    }

    /**
     * The line under the day's heart rate on a day the clocks went back (issue #97), for example "The clocks
     * went back one hour on this day (03:00 became 02:00), so 02:00 to 03:00 happened twice. ...", or null on
     * other days (the hour the clocks skip needs no note: no time in it is shown).
     */
    public static String repeatText(Change c) {
        if (c == null || c.minutes >= 0) return null;
        return "The clocks went back " + amount(-c.minutes) + " on this day (" + c.from + " became " + c.to + "), so " + c.to + " to "
                + c.from + " happened twice. Times in it say first or second time.";
    }

    /**
     * " (first time)" or " (second time)" after a clock time in the hour that happens twice when the clocks go
     * back (issue #97), so two moments that read "02:22" can be told apart; "" at every other moment.
     */
    public static String repeatNote(ZoneId zone, double unix) {
        if (zone == null || Double.isNaN(unix)) return "";
        Instant t = Instant.ofEpochSecond((long) Math.floor(unix));
        java.util.List<java.time.ZoneOffset> offs = zone.getRules().getValidOffsets(java.time.LocalDateTime.ofInstant(t, zone));
        if (offs.size() < 2) return "";
        int first = Integer.MIN_VALUE;
        // the first pass is the one with the larger offset (summer time before the clocks go back)
        for (java.time.ZoneOffset o : offs) first = Math.max(first, o.getTotalSeconds());
        return zone.getRules().getOffset(t).getTotalSeconds() == first ? " (first time)" : " (second time)";
    }

    /** The zone, or null when the id is empty or unknown on this phone. */
    public static ZoneId zone(String id) {
        if (id == null || id.isEmpty()) return null;
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A readable name without the internet: the city part of the id with spaces ("New York time",
     * "Buenos Aires time"); ids without a city read as their offset ("UTC", "UTC+5", "UTC+5:30").
     */
    public static String zoneName(String id) {
        int slash = id.lastIndexOf('/');
        if (slash > 0 && slash < id.length() - 1 && !id.startsWith("Etc/")) {
            String city = id.substring(slash + 1).replace('_', ' ');
            if (Character.isLetter(city.charAt(0)) && !city.startsWith("GMT") && !city.startsWith("UTC")) return city + " time";
        }
        ZoneId z = zone(id);
        if (z == null) return id;
        int s = z.getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        if (s == 0) return "UTC";
        int m = Math.abs(s) / 60;
        return "UTC" + (s > 0 ? "+" : "-") + (m / 60) + (m % 60 == 0 ? "" : String.format(java.util.Locale.US, ":%02d", m % 60));
    }

    /** "one hour", "2 hours", "30 minutes", "4 hours 30 minutes". */
    static String amount(int min) {
        int h = min / 60, m = min % 60;
        String hs = h == 0 ? "" : h == 1 ? "one hour" : h + " hours";
        if (m == 0) return hs;
        return (hs.isEmpty() ? "" : hs + " ") + m + " minutes";
    }

    static String hm(LocalTime t) {
        return String.format(java.util.Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
    }
}
