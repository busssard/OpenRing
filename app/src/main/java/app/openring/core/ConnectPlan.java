package app.openring.core;

/**
 * How a ring task reaches the ring (issue #195): direct connects to the saved address, then a scan, then a connect to
 * what the scan heard, in rounds for the errors {@link SyncError#retryable} keeps. A sync the person starts (and Live,
 * the games, Settings and pairing) keeps the long scan; the background job, which nobody watches and which runs with
 * the screen off, scans briefly, filtered only and at balanced duty, so a ring that is away costs about half the radio
 * time.
 */
public final class ConnectPlan {
    /** True for the plan of the background job. */
    public final boolean background;
    /** Direct connects to the saved address before any scan. */
    public final int directTries;
    /** Time limit of each direct connect, ms. */
    public final long directMs;
    /** Pause between two direct connects, ms. */
    public final long directGapMs;
    /** Length of the scan after the direct connects failed, ms. */
    public final long scanMs;
    /** The scan listens all the time (low latency); false: Android's balanced duty. */
    public final boolean lowLatency;
    /** The second half of the scan runs without a filter, for rings that only advertise a name (ignored with the screen off). */
    public final boolean widen;
    /** Time limit of the connect to the ring the scan heard, ms. */
    public final long connectMs;
    /** Connect and download rounds of a sync, for errors worth another try. */
    public final int rounds;
    /** Pause between two rounds, ms. */
    public final long roundGapMs;

    /** A sync the person starts, Live, the games, Settings and pairing: the plan as it was before issue #195. */
    public static final ConnectPlan PERSON = new ConnectPlan(false, 2, 12000, 1500, 40000, true, true, 20000, 3, 2500);
    /** The background job: a short, filtered, balanced scan and one round fewer (issue #195). */
    public static final ConnectPlan BACKGROUND = new ConnectPlan(true, 2, 12000, 1500, 10000, false, false, 20000, 2, 2500);

    public ConnectPlan(boolean background, int directTries, long directMs, long directGapMs, long scanMs, boolean lowLatency, boolean widen,
            long connectMs, int rounds, long roundGapMs) {
        this.background = background;
        this.directTries = directTries;
        this.directMs = directMs;
        this.directGapMs = directGapMs;
        this.scanMs = scanMs;
        this.lowLatency = lowLatency;
        this.widen = widen;
        this.connectMs = connectMs;
        this.rounds = rounds;
        this.roundGapMs = roundGapMs;
    }

    public static ConnectPlan of(boolean background) {
        return background ? BACKGROUND : PERSON;
    }

    /** Radio time of one attempt with the ring out of reach: every direct connect runs out, then the whole scan hears nothing. */
    public long awayMs() {
        return directTries * directMs + Math.max(0, directTries - 1) * directGapMs + scanMs;
    }

    /**
     * The longest a sync can try to connect: the ring is heard at the end of every scan but never connects, in every
     * round (the "not reached" case, retried).
     */
    public long worstMs() {
        return rounds * (awayMs() + connectMs) + Math.max(0, rounds - 1) * roundGapMs;
    }

    /** One line for the Bluetooth log, so the data check shows which plan ran. */
    @Override
    public String toString() {
        return "direct " + directTries + " x " + directMs / 1000 + " s, scan " + scanMs / 1000 + " s "
                + (lowLatency ? "low latency" : "balanced") + (widen ? " then unfiltered" : " filtered") + ", " + rounds + " rounds";
    }
}
