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
