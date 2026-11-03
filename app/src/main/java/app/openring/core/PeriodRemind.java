package app.openring.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import app.openring.core.analysis.Cycles;

/**
 * The reminder before the expected period (issue #252, phase 1a2), as plain rules so they run in the JVM tests; the
 * alarm and the notification are in {@code sync/PeriodReminder}.
 *
 * <ul>
 * <li>{@link #DAYS} days before the earliest day of the expected range, at the chosen time of day (default 9:00) on the
 * phone's clock in the zone it is in now. A moment that has already passed while that day is still to come (the phone
 * was off, the estimate moved earlier, the reminder was just switched on) is sent now.</li>
 * <li>None while the reminder is off, without an estimate (no period logged, waiting for the next start, or "My periods
 * have stopped"), while a period runs, once the range has begun, and never twice for one cycle: the key is the start of
 * the period the cycle began with, so a moved estimate within the same cycle sends nothing new.</li>
 * <li>Only from the person's own log (the real file); the demo's made up log never schedules one.</li>
 * </ul>
 */
public final class PeriodRemind {
    private PeriodRemind() {}

    /** The choices: Off, 1, 2, 3 or 5 days before. */
    public static final int[] DAYS = {0, 1, 2, 3, 5};
    /** 9:00, in minutes after midnight. */
    public static final int DEFAULT_MINUTE = 9 * 60;
    /** An alarm that comes up to this much before its moment still sends (Android may move inexact alarms). */
    public static final long EARLY_MS = 15 * 60 * 1000L;

    public static boolean validDays(int d) {
        for (int x : DAYS) if (x == d) return true;
        return false;
    }

    /** A time of day within one day, else the default. */
    public static int minute(int m) {
        return m >= 0 && m < 24 * 60 ? m : DEFAULT_MINUTE;
    }

    /** "Off", "1 day", "2 days". */
    public static String chip(int days) {
        return days <= 0 ? "Off" : Texts.count(days, "day", "days");
    }

    /** "At 9:00". */
    public static String time(int minute) {
        int m = minute(minute);
        return "At " + (m / 60) + ":" + String.format(Locale.ROOT, "%02d", m % 60);
    }

    /** Why there is no reminder, or {@link #PLANNED}. */
    public static final int PLANNED = 0, OFF = 1, NO_LOG = 2, STOPPED = 3, WAITING = 4, RUNNING = 5, BEGUN = 6, SENT = 7;

    /** The next reminder: when ({@link #at}, unix milliseconds) and for which cycle ({@link #key}). */
    public static final class Plan {
        public final int why;
        public final long at;
        public final String key;

        public Plan(int why, long at, String key) {
            this.why = why;
            this.at = at;
            this.key = key;
        }

        public boolean planned() {
            return why == PLANNED;
        }
    }

    /**
     * The next reminder from {@code cy} (the person's own log, null when there is none), {@code days} before the range at
     * {@code minute} in {@code zone}, as of {@code now} (unix milliseconds); {@code sent} is the key of the cycle already
     * reminded of. A plan that is not {@link Plan#planned} says why.
     */
    public static Plan plan(Cycles cy, int days, int minute, ZoneId zone, long now, String sent) {
        if (days <= 0) return new Plan(OFF, 0, null);
        if (cy == null || cy.periods.isEmpty()) return new Plan(NO_LOG, 0, null);
        if (cy.stopped) return new Plan(STOPPED, 0, null);
        if (cy.running != null) return new Plan(RUNNING, 0, null);
        if (!cy.estimates()) return new Plan(WAITING, 0, null);
        String key = cy.periods.get(cy.periods.size() - 1).start.toString();
        LocalDate first = cy.next.minusDays(cy.range);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (!today.isBefore(first)) return new Plan(BEGUN, 0, key);
        if (key.equals(sent)) return new Plan(SENT, 0, key);
        int m = minute(minute);
        long at = ZonedDateTime.of(first.minusDays(days), LocalTime.of(m / 60, m % 60), zone).toInstant().toEpochMilli();
        return new Plan(PLANNED, Math.max(at, now), key);
    }

    /** At the alarm: whether {@code p} is due now. */
    public static boolean due(Plan p, long now) {
        return p != null && p.planned() && p.at <= now + EARLY_MS;
    }

    public static final String TITLE = "Period expected soon";
    /** All the lock screen shows of it. */
    public static final String PUBLIC_TITLE = "Open Ring reminder";

    /** "Your next period may start in about 2 days (expected 6 to 12 Nov)." on {@code today}. */
    public static String text(Cycles cy, LocalDate today, Locale loc) {
        LocalDate first = cy.next.minusDays(cy.range);
        long in = ChronoUnit.DAYS.between(today, first);
        String when = in <= 0 ? "any day now" : in == 1 ? "tomorrow" : "in about " + in + " days";
        return "Your next period may start " + when + " (expected " + cy.span(first, cy.next.plusDays(cy.range), loc) + ").";
    }

    /** The line in Settings under the choices: when the next reminder comes, or why none. */
    public static String status(Plan p, ZoneId zone, long now, Locale loc) {
        switch (p.why) {
            case OFF: return "Reminders are off.";
            case NO_LOG: return "No reminder yet: it starts once you have logged a period.";
            case STOPPED: return "No reminders: you said your periods have stopped.";
            case RUNNING: return "No reminder while a period is logged.";
            case WAITING: return "No reminder until you log your next period.";
            case BEGUN: return "No reminder for this period, it may start any day now.";
            case SENT: return "The reminder for this period was sent.";
            default:
                if (p.at <= now + 60 * 1000L) return "Next reminder: in a few minutes.";
                ZonedDateTime z = Instant.ofEpochMilli(p.at).atZone(zone);
                return "Next reminder: " + z.format(DateTimeFormatter.ofPattern("EEE d MMM", loc)) + " at " + z.getHour() + ":"
                        + String.format(Locale.ROOT, "%02d", z.getMinute()) + ".";
        }
    }
}
