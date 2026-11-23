package app.openring.data;

import android.content.Context;

/**
 * Delete all local data (Settings, issue #380 phase 1a: one tested method instead of calls in the screen). When the real history
 * is deleted, everything below runs under the lock every write of a night listening for sleep sounds takes
 * ({@link SoundNight#deleteHolding}, phase 1b): the night writes nothing meanwhile, and only once all of it is done the night is
 * closed, so not a row and not a clip file of it is written afterwards and the listening service ends by itself. When the
 * delete fails, the night goes on as before (review of #450). In this order:
 * <ol>
 * <li>the history in use ({@link Analyzer#deleteAll}: every table in one transaction, waiting for a running analysis; when it
 * fails nothing was deleted and nothing below runs),</li>
 * <li>the copies made before updates ({@link BeforeUpdate#deleteAll}; the copies in the person's backup folder stay, #242),</li>
 * <li>the sleep sound clips of the history that was deleted ({@link Sounds#folder(Context, Db)}): the demo's folder in demo
 * mode, the real folder otherwise. The other history is not touched, so neither are its clips: its marks keep their files
 * (folders never crossed). A file that cannot be deleted is removed by the next sweep, since no row names it any more.</li>
 * </ol>
 * The caller holds the ring lock (no sync writes meanwhile).
 */
public final class DeleteAll {
    private DeleteAll() {}

    public static void run(final Context c) {
        final Db db = Db.get(c);
        Runnable delete = new Runnable() {
            @Override
            public void run() {
                Analyzer.deleteAll(db);
                BeforeUpdate.deleteAll(c);
                Sounds.deleteTree(Sounds.folder(c, db));
            }
        };
        // the night listens into the real file and folder: held, then closed, when those are deleted (the demo's keeps it)
        if (Db.DEMO.equals(db.fileName)) delete.run();
        else SoundNight.deleteHolding(delete);
    }
}
