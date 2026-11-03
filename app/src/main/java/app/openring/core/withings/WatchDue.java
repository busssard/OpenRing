package app.openring.core.withings;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When the background job syncs the watch (issue #251, phase 1b). The person picks how often: by hand, once, twice or
 * 4 times a day. Each choice has clock times in the phone's zone; the job runs about every 3 hours, so a sync is due at
 * the first run after the newest of these times that has passed since the last good sync. A failed attempt is tried
 * again at a later run, 2.5 hours on at the earliest, so a watch left at home costs one short try per job run, not more.
 */
public final class WatchDue {
    private WatchDue() {}

    /** The choices, in the order of the Settings chips; 1 is the default. */
    public static final int[] PER_DAY = {0, 1, 2, 4};
    public static final int DEFAULT = 1;
    public static final String[] LABELS = {"By hand", "Once a day", "Twice a day", "4 times a day"};
    /** A failed attempt is tried again after this long at the earliest (seconds). */
    public static final long RETRY_S = 150 * 60;

    public static boolean valid(int perDay) {
        for (int p : PER_DAY) if (p == perDay) return true;
        return false;
    }

    /** The clock hours of a choice. */
    public static int[] hours(int perDay) {
        switch (perDay) {
            case 1: return new int[] {7};
            case 2: return new int[] {7, 19};
            case 4: return new int[] {1, 7, 13, 19};
            default: return new int[0];
        }
    }

    /** The line under the chips. */
    public static String explain(int perDay) {
        switch (perDay) {
            case 1: return "Syncs about 07:00. The background job runs about every 3 hours, so it can be later.";
            case 2: return "Syncs about 07:00 and 19:00. The background job runs about every 3 hours, so it can be later.";
            case 4: return "Syncs about 01:00, 07:00, 13:00 and 19:00. More syncs cost more of the watch's battery.";
            default: return "Syncs only when you tap Sync the watch now.";
        }
    }

    /**
     * The newest clock time of {@code perDay} at or before {@code now} (unix seconds) in {@code zone}, 0 for By hand.
     * On a day the clocks change, a time that does not exist moves on by the change, as the clock does.
     */
    public static long lastSlot(long now, int perDay, ZoneId zone) {
        int[] hs = hours(perDay);
        if (hs.length == 0) return 0;
        LocalDate d = Instant.ofEpochSecond(now).atZone(zone).toLocalDate();
        long best = 0;
        for (int back = 0; back <= 2 && best == 0; back++) {
            LocalDate day = d.minusDays(back);
            for (int h : hs) {
                long t = ZonedDateTime.of(day.atTime(h, 0), zone).toEpochSecond();
                if (t <= now && t > best) best = t;
            }
        }
        return best;
    }

    /**
     * True when the job should sync the watch now: a choice other than By hand, the newest clock time passed after the
     * last good sync ({@code lastOk}), and no attempt since that time or the last one {@link #RETRY_S} ago. A last sync or
     * attempt that lies in the future (the clock was set back) does not hold syncing off.
     */
    public static boolean due(long now, int perDay, long lastOk, long lastAttempt, ZoneId zone) {
        long slot = lastSlot(now, perDay, zone);
        if (slot == 0) return false;
        if (lastOk > now) lastOk = 0;
        if (lastAttempt > now) lastAttempt = 0;
        if (lastOk >= slot) return false;
        return lastAttempt < slot || now - lastAttempt >= RETRY_S;
    }
}
