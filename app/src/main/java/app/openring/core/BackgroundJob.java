package app.openring.core;

/**
 * Whether the background sync job must be scheduled, kept or cancelled (issue #193), kept apart from
 * Android so the JVM tests cover it. Android stops a job that is running when {@code JobScheduler.schedule}
 * replaces it, and the main screen asks on every resume, so the job is scheduled only when nothing equal
 * is pending. {@code sync/SyncJob} applies it.
 */
public final class BackgroundJob {
    private BackgroundJob() {}

    /** About every 3 hours, Android picks the moment within the last hour of each period. */
    public static final long INTERVAL_MS = 3 * 60 * 60 * 1000L, FLEX_MS = 60 * 60 * 1000L;

    public enum Action { KEEP, SCHEDULE, CANCEL }

    /** A sync or sync attempt less than this long ago (seconds) makes the main screen and the job skip theirs (issue #194). */
    public static final long RECENT_S = 30 * 60;

    /**
     * True when the last sync or attempt ({@code lastSync}, {@code lastAttempt}, unix seconds, 0 for none) is at most
     * {@link #RECENT_S} before {@code now}. One that lies after now (the phone clock was set back) is not recent, so it
     * cannot hold off syncing until the clock catches up.
     */
    public static boolean recent(long now, long lastSync, long lastAttempt) {
        long since = now - Math.max(lastSync, lastAttempt);
        return since >= 0 && since <= RECENT_S;
    }

    /** The job's log line when it leaves the ring out because it synced a short while ago (issue #194). */
    public static final String SKIPPED_RECENT = "background sync skipped: the last sync was less than 30 minutes ago";

    /** What one run of the job sees (issue #329, phase 1a): read by {@code sync/SyncJob}, decided by {@link #due}. */
    public static final class Inputs {
        /** The ring is in use by another task (a sync, Live, a game): the job ends at once and reads nothing else. */
        public boolean ringBusy;
        /** Background sync switched on in Settings. */
        public boolean backgroundSync;
        /** {@link KeyState} of the saved ring key. */
        public int keyState = KeyState.NONE;
        /** Demo mode is shown: the watch never syncs then. */
        public boolean demo;
        /** A watch is added (its address is saved). */
        public boolean watchAdded;
        /** The watch's syncs per day and its last good sync and last attempt (unix seconds, 0 none), as {@code WatchDue} reads them. */
        public int watchPerDay;
        public long watchLastOk, watchLastAttempt;
        /** The ring's last sync and last attempt (unix seconds, 0 none). */
        public long lastSync, lastAttempt;
        /** Now, unix seconds, and the phone's zone (the watch's clock times). */
        public long now;
        public java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    }

    /** What the job does in one run. */
    public static final class Due {
        /** Sync the ring (first, as before). */
        public final boolean ring;
        /** Sync the watch after the ring. */
        public final boolean watch;
        /** The line to log ({@link #SKIPPED_RECENT}), null for none. */
        public final String log;

        Due(boolean ring, boolean watch, String log) {
            this.ring = ring;
            this.watch = watch;
            this.log = log;
        }

        /** Anything to do; when not, the job ends at once. */
        public boolean run() {
            return ring || watch;
        }
    }

    /**
     * The job's decision, kept apart from Android so the JVM tests cover it (issue #329, phase 1a, as {@code SyncJob} decided
     * it before). While the ring is busy nothing runs. The ring part runs when background sync is on, the key can be read
     * and the last sync or attempt is not {@link #recent}; a recent one is logged ({@link #SKIPPED_RECENT}) when the ring
     * part was on. The watch part runs outside demo mode when a watch is added and one of its clock times passed
     * ({@code WatchDue}), also when the ring part is skipped.
     */
    public static Due due(Inputs in) {
        if (in.ringBusy) return new Due(false, false, null);
        boolean ringPart = in.backgroundSync && KeyState.syncs(in.keyState);
        boolean watchPart = !in.demo && in.watchAdded
                && app.openring.core.withings.WatchDue.due(in.now, in.watchPerDay, in.watchLastOk, in.watchLastAttempt, in.zone);
        boolean ringRecent = recent(in.now, in.lastSync, in.lastAttempt);
        return new Due(ringPart && !ringRecent, watchPart, ringPart && ringRecent ? SKIPPED_RECENT : null);
    }

    /** The parts of a job this app sets; a pending job is compared with the wanted one by these. */
    public static final class Spec {
        public final String service;
        public final boolean periodic, persisted, batteryNotLow;
        /** Runs only while the phone charges (the automatic backup, issue #242); false for the sync. */
        public final boolean charging;
        public final long interval, flex;

        public Spec(String service, boolean periodic, long interval, long flex, boolean persisted, boolean batteryNotLow) {
            this(service, periodic, interval, flex, persisted, batteryNotLow, false);
        }

        public Spec(String service, boolean periodic, long interval, long flex, boolean persisted, boolean batteryNotLow, boolean charging) {
            this.service = service;
            this.periodic = periodic;
            this.interval = interval;
            this.flex = flex;
            this.persisted = persisted;
            this.batteryNotLow = batteryNotLow;
            this.charging = charging;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Spec)) return false;
            Spec s = (Spec) o;
            return (service == null ? s.service == null : service.equals(s.service)) && periodic == s.periodic
                    && interval == s.interval && flex == s.flex && persisted == s.persisted && batteryNotLow == s.batteryNotLow
                    && charging == s.charging;
        }

        @Override
        public int hashCode() {
            int h = service == null ? 0 : service.hashCode();
            h = 31 * h + (periodic ? 1 : 0);
            h = 31 * h + (int) (interval ^ (interval >>> 32));
            h = 31 * h + (int) (flex ^ (flex >>> 32));
            h = 31 * h + (persisted ? 1 : 0);
            h = 31 * h + (batteryNotLow ? 1 : 0);
            return 31 * h + (charging ? 1 : 0);
        }

        @Override
        public String toString() {
            return service + (periodic ? " every " + interval / 60000 + " min (flex " + flex / 60000 + ")" : " once")
                    + (persisted ? ", persisted" : "") + (batteryNotLow ? ", battery not low" : "") + (charging ? ", charging" : "");
        }
    }

    /** The background sync job as this version wants it. */
    public static Spec wanted(String service) {
        return new Spec(service, true, INTERVAL_MS, FLEX_MS, true, true);
    }

    /**
     * What to do now.
     *
     * @param on      background sync is switched on and a ring key is saved
     * @param known   the pending job could be read; when not, it acts as before issue #193 (schedule, or cancel when off)
     * @param pending the job Android has under this id (also while it runs), or null for none
     * @param wanted  the job this version wants
     */
    public static Action decide(boolean on, boolean known, Spec pending, Spec wanted) {
        if (!known) return on ? Action.SCHEDULE : Action.CANCEL;
        if (!on) return pending != null ? Action.CANCEL : Action.KEEP;
        return wanted.equals(pending) ? Action.KEEP : Action.SCHEDULE;
    }
}
