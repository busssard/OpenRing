package app.openring.core;

/**
 * Setup's restore and pairing never run at the same time (review of #248). A restore that waits for a sync (issue #219)
 * can take minutes; a pairing started meanwhile would write a new key to the ring, and the restore would then save the
 * backup's key on the phone, so the ring could no longer be reached (the rule of issue #218: the phone always keeps the
 * key the ring has). A restore counts from the moment the person picked the file until it ended (refused, cancelled,
 * restored, or the screen left for good with nothing running); only one restore runs at a time. One instance per app
 * ({@code SetupActivity}), so a rebuilt Setup screen sees the restore of the one before.
 */
public final class SetupTasks {
    /** Pairing refused while a restore runs or waits for the person's choice. */
    public static final String PAIR_REFUSED = "A restore is under way. Wait for it to finish, then pair.";
    /** A restore refused while a pairing runs. */
    public static final String RESTORE_REFUSED = "Pairing is under way. Wait for it to finish, then restore.";
    /** A second restore refused while one runs. */
    public static final String RESTORE_RUNS = "A restore is already under way. Wait for it to finish.";

    private boolean restoring;

    /** A restore starts: null, and it counts until {@link #restoreEnded}; otherwise the sentence why it does not. */
    public synchronized String startRestore(boolean pairing) {
        if (pairing) return RESTORE_REFUSED;
        if (restoring) return RESTORE_RUNS;
        restoring = true;
        return null;
    }

    /** The restore ended in any way; also safe when none runs. */
    public synchronized void restoreEnded() {
        restoring = false;
    }

    public synchronized boolean restoring() {
        return restoring;
    }

    /** Null when a pairing may start now, otherwise the sentence why not. */
    public synchronized String pairRefusal() {
        return restoring ? PAIR_REFUSED : null;
    }
}
