package app.openring.core;

import app.openring.core.withings.Frame;
import app.openring.core.withings.Proto;
import app.openring.core.withings.Session;
import app.openring.core.withings.WatchSetup;

/**
 * What the screens show of a sync running now (issue #478): the ring's line with a rough percent, the watch's step n of
 * N with a percent inside its data steps, and how often the screen is told. Display only: the Bluetooth code hands it
 * what it already has (the ring's bytes left, the watch session's steps and the messages it stored) and nothing here is
 * ever sent to the ring or the watch. No method throws for any input, so it can never end or hold up a sync.
 */
public final class SyncProgress {
    private SyncProgress() {}

    /** Within a step the screen is told at most this often (ms); a new step is shown at once. */
    public static final long SHOW_MS = 250;

    /** The line under the watch's line in Settings, Watch, that explains its numbers. */
    public static final String WATCH_EXPLAIN = "The steps take different times. The percent is how far the records of this step have come, from the last sync to now.";

    // ------------------------------------------------------------------------------------------------- the ring

    /**
     * The ring download's share done, 0 to 1, or -1 while unknown: {@code 1 - left / firstLeft}, where {@code firstLeft}
     * is the first "bytes left" above 0 the ring reported in this sync (so the first batch counts as 0). It never goes
     * below {@code shown} (a ring that records while it sends can report a little more left), and stays under 1 while the
     * ring says something is left.
     */
    public static double ringFraction(long left, long firstLeft, double shown) {
        double f;
        if (firstLeft <= 0 || left < 0) f = -1;
        else if (left == 0) f = 1;
        else f = Math.max(0, Math.min(0.99, 1 - left / (double) firstLeft));
        if (!(shown >= 0 && shown <= 1)) return f;
        if (f < 0) return shown;
        return Math.max(f, left == 0 ? shown : Math.min(shown, 0.99));
    }

    /** "about 40 %": whole percent, rounded down. */
    public static String about(double fraction) {
        int p = (int) Math.floor(Math.max(0, Math.min(1, fraction)) * 100);
        return "about " + p + " %";
    }

    /**
     * The ring's status line during its download: "Syncing your ring" before the first batch, "Syncing your ring, about
     * 40 % (1234 events so far)" with a known total, "Syncing your ring (1234 events so far)" once nothing is left or while
     * no total is known; "in the background" for the background job's sync.
     */
    public static String ringLine(int events, double fraction, boolean background) {
        StringBuilder b = new StringBuilder("Syncing your ring");
        if (background) b.append(" in the background");
        if (fraction >= 0 && fraction < 1) b.append(", ").append(about(fraction));
        if (events > 0) b.append(" (").append(Texts.count(events, "event", "events")).append(" so far)");
        return b.toString();
    }

    /** The title of the sync notification (sync/SyncService). */
    public static final String NOTIFICATION_TITLE = "Syncing your ring";

    /**
     * The sync notification's text under {@link #NOTIFICATION_TITLE}: a {@link #ringLine} without the words the title
     * already says, "About 40 % (1234 events so far)", "1234 events so far", or "Copying what the ring recorded" before the
     * first batch; any other line ("Connecting", a failure) as it is. The notification itself shows the sync runs in the
     * background, so "in the background" is left out too.
     */
    public static String notificationText(String status) {
        if (status == null) return "";
        if (!status.startsWith(NOTIFICATION_TITLE)) return status;
        String rest = status.substring(NOTIFICATION_TITLE.length());
        if (rest.startsWith(" in the background")) rest = rest.substring(" in the background".length());
        if (rest.startsWith(",")) rest = rest.substring(1);
        rest = rest.trim();
        if (rest.startsWith("(") && rest.endsWith(")") && rest.indexOf('(', 1) < 0) rest = rest.substring(1, rest.length() - 1).trim();
        if (rest.isEmpty()) return "Copying what the ring recorded";
        return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    // ------------------------------------------------------------------------------ guarded calls of the display

    /** A change to what the screens show; true tells them now. */
    public interface Show {
        boolean apply();
    }

    /**
     * Applies {@code s} and runs {@code tell} when it says so. Any RuntimeException of either is swallowed: the display can
     * never end, change or hold up a sync (WatchSync calls every change of {@code WatchSync.SHOWN} through this).
     */
    public static void show(Show s, Runnable tell) {
        try {
            if (s.apply()) tell.run();
        } catch (RuntimeException e) {
            // display only: the sync goes on as it would without it
        }
    }

    /**
     * A session's step reports for a sync: first handed on to {@code also} (may be null) exactly as before, unguarded, then
     * to {@code display}, whose RuntimeException is swallowed. So a broken display gives the session the same steps.
     */
    public static Session.Progress guard(final Session.Progress also, final Session.Progress display) {
        return new Session.Progress() {
            @Override
            public void step(String key, int state) {
                if (also != null) also.step(key, state);
                try {
                    display.step(key, state);
                } catch (RuntimeException e) {
                    // display only
                }
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ the watch

    /** The plain name of a watch session step ({@link Session} and {@link WatchSetup} keys); the key itself for any other. */
    public static String stepName(String key) {
        if (key == null) return "";
        switch (key) {
            case Session.HELLO: return "greeting the watch";
            case Session.SIGN_IN: return "signing in";
            case WatchSetup.CONNECT: return "opening the session";
            case WatchSetup.BATTERY: return "battery";
            case WatchSetup.TIME: return "setting the time";
            case WatchSetup.USER: return "your profile";
            case WatchSetup.GOAL: return "step goal";
            case WatchSetup.DISTANCE: return "distance unit";
            case WatchSetup.CLOCK: return "clock format";
            case Session.ACTIVITY: return "activity records";
            case Session.MOVEMENT: return "movement records";
            case Session.HEART: return "heart rate";
            case WatchSetup.ALARM:
            case WatchSetup.ALARM_ON: return "alarm";
            case WatchSetup.HANDS_STOP: return "ending hand setting";
            case WatchSetup.LANGUAGE: return "language";
            case WatchSetup.SCREENS: return "screens";
            case WatchSetup.FINISHED: return "finishing the setup";
            default: return key;
        }
    }

    /** True for the three data steps, the only ones with a percent. */
    static boolean dataStep(String key) {
        return Session.ACTIVITY.equals(key) || Session.MOVEMENT.equals(key) || Session.HEART.equals(key);
    }

    /**
     * One watch sync as the screens show it. The session's thread tells it about the start, the steps and every stored
     * message; the screen thread reads {@link #line} and {@link #fraction}. Each method that may change the screen
     * returns true when the screen should be told now ({@link #SHOW_MS}).
     */
    public static final class Watch {
        private boolean running, background, saving, planned, connecting;
        /** Step now, 1 based (0 before the hello), and how many there are: the hello plus the session's own steps. */
        private int index, total;
        private String key;
        private long now, activitySince, movementSince, heartSince;
        /** The newest record time seen in the current data step (unix seconds), 0 for none. */
        private long newest;
        private long lastShown = Long.MIN_VALUE / 2;
        private double shownFraction = -1;

        /**
         * A sync begins: "Syncing your watch: getting ready" while it decodes what earlier syncs stored and waits for the
         * ring to be free (review of 2fc3d99: that can take long, and nothing is connecting yet).
         */
        public synchronized boolean start(boolean background) {
            running = true;
            this.background = background;
            saving = planned = connecting = false;
            index = total = 0;
            key = null;
            newest = 0;
            shownFraction = -1;
            return true;
        }

        /** The sync goes to the watch now (it holds the ring lock): "Syncing your watch: connecting". */
        public synchronized boolean connecting() {
            if (!running) return false;
            connecting = true;
            return true;
        }

        /** The session's steps after the hello, and the times its data requests ask from (unix seconds). */
        public synchronized void plan(int steps, long now, long activitySince, long movementSince, long heartSince) {
            total = 1 + Math.max(0, steps);
            planned = true;
            this.now = now;
            this.activitySince = activitySince;
            this.movementSince = movementSince;
            this.heartSince = heartSince;
        }

        /** {@link Session.Progress#step}: a started step moves on (the sign in stays inside the hello's step). */
        public synchronized boolean step(String k, int state, long nowMs) {
            if (!running || k == null || state != Session.STARTED) return false;
            if (Session.SIGN_IN.equals(k)) {
                key = k;
            } else {
                index = Session.HELLO.equals(k) ? 1 : index + 1;
                if (total > 0) index = Math.min(index, total);
                key = k;
                newest = 0;
            }
            lastShown = nowMs;
            return true;
        }

        /** A message the session stored: its record times move the data step's percent. True when the screen is due. */
        public synchronized boolean message(byte[] msg, long nowMs) {
            if (!running || !dataStep(key)) return false;
            try {
                if (!Frame.whole(msg) || Frame.fromWatch(msg)) return false;
                for (Frame.Part p : Frame.parts(msg)) {
                    if (p.type == Proto.REC_TIME && p.value.length >= 4) newest = Math.max(newest, Frame.u32(p.value, 0));
                }
            } catch (RuntimeException e) {
                return false;
            }
            return due(nowMs);
        }

        /** The session ended, what came is being decoded: "saving what came". */
        public synchronized boolean saving() {
            if (!running) return false;
            saving = true;
            return true;
        }

        /** The sync is over (also after a failure): no line any more. */
        public synchronized boolean end() {
            running = false;
            return true;
        }

        private boolean due(long nowMs) {
            if (nowMs - lastShown < SHOW_MS && nowMs >= lastShown) return false;
            lastShown = nowMs;
            return true;
        }

        /** The current data step's share, 0 to 0.99, or -1 when it is no data step or no record came yet. */
        synchronized double within() {
            if (!dataStep(key) || newest <= 0) return -1;
            long since = Session.ACTIVITY.equals(key) ? activitySince : Session.MOVEMENT.equals(key) ? movementSince : heartSince;
            if (now <= since) return -1;
            double f = (newest - since) / (double) (now - since);
            return Math.max(0, Math.min(0.99, f));
        }

        /** The line, or null when no watch sync runs. */
        public synchronized String line() {
            if (!running) return null;
            StringBuilder b = new StringBuilder("Syncing your watch");
            if (background) b.append(" in the background");
            b.append(": ");
            if (saving) return b.append("saving what came").toString();
            if (index <= 0 || key == null) return b.append(connecting ? "connecting" : "getting ready").toString();
            if (planned) b.append("step ").append(index).append(" of ").append(total).append(", ");
            b.append(stepName(key));
            double w = within();
            if (w >= 0) b.append(" (").append(about(w)).append(")");
            return b.toString();
        }

        /** The bar: (steps done + the current step's percent) / N, 0 to 1; it never goes back; -1 before the steps are known. */
        public synchronized double fraction() {
            if (!running || !planned || total <= 0) return -1;
            if (saving) return shownFraction = 1;
            double w = Math.max(0, within());
            double f = Math.max(0, Math.min(1, (Math.max(0, index - 1) + w) / total));
            shownFraction = Math.max(shownFraction, f);
            return shownFraction;
        }
    }
}
