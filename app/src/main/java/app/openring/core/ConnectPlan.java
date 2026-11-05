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
    /** Rounds of a sync while the ring is not reached (the connect failed), for errors worth another try. */
    public final int rounds;
    /**
     * Rounds of a sync once a round had connected and then failed (a broken off download, issue #157): 3 in both plans,
     * the ring is near then and the next round continues the download.
     */
    public final int connectedRounds = 3;
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

    /** The most rounds a sync can run, whatever failed. */
    public int maxRounds() {
        return Math.max(rounds, connectedRounds);
    }

    /**
     * Whether a sync whose round {@code attempt} (from 1) failed with an error worth another try runs another round:
     * {@code connected} says the round reached the ring and failed after that (then {@link #connectedRounds} count),
     * otherwise the connect failed (then {@link #rounds}, fewer in the background).
     */
    public boolean another(int attempt, boolean connected) {
        return attempt < (connected ? connectedRounds : rounds);
    }

    /** Radio time of one attempt with the ring out of reach: every direct connect runs out, then the whole scan hears nothing. */
    public long awayMs() {
        return directTries * directMs + Math.max(0, directTries - 1) * directGapMs + scanMs;
    }

    /**
     * The longest a sync can try to connect when it never reaches the ring: the ring is heard at the end of every scan
     * but never connects, in every round (the "not reached" case, retried). Not counted: rounds that connected and broke
     * off later (they take download time and get {@link #connectedRounds}), and the extra scans after another person's
     * ring answered and refused the key (at most 2, each {@link #scanMs} plus {@link #connectMs}).
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
