package app.openring.data;

import android.content.Context;

/**
 * Delete all local data (Settings, issue #380 phase 1a: one tested method instead of calls in the screen). In this order:
 * <ol>
 * <li>when the real history is deleted, a night listening for sleep sounds is stopped ({@link SoundNight#stopRunning}, phase 1b, Design review of #380): under the
 * lock every write of the night takes, so once it returns not a row and not a clip file of it is written any more, and the
 * listening service ends by itself,</li>
 * <li>the history in use ({@link Analyzer#deleteAll}: every table in one transaction, waiting for a running analysis; when it
 * fails nothing was deleted and nothing below runs),</li>
 * <li>the copies made before updates ({@link BeforeUpdate#deleteAll}; the copies in the person's backup folder stay, #242), only
 * when the deleted file is the real one: those copies are always copies of the real file, so emptying the demo file in demo
 * mode keeps them (issue #425). Tied to the file, not to the demo switch, so a mode change during the run cannot mix the two,</li>
 * <li>the sleep sound clips of the history that was deleted ({@link Sounds#folder(Context, Db)}): the demo's folder in demo
 * mode, the real folder otherwise. The other history is not touched, so neither are its clips: its marks keep their files
 * (folders never crossed). A file that cannot be deleted is removed by the next sweep, since no row names it any more.</li>
 * </ol>
 * The caller holds the ring lock (no sync writes meanwhile).
 */
public final class DeleteAll {
    private DeleteAll() {}

    public static void run(Context c) {
        Db db = Db.get(c);
        // the night listens into the real file and folder: stopped first when those are deleted (the demo's Delete all keeps it)
        if (!Db.DEMO.equals(db.fileName)) SoundNight.stopRunning();
        Analyzer.deleteAll(db);
        if (!Db.DEMO.equals(db.fileName)) BeforeUpdate.deleteAll(c);
        Sounds.deleteTree(Sounds.folder(c, db));
    }
}
