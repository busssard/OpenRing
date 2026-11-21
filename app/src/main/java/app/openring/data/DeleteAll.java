package app.openring.data;

import android.content.Context;

/**
 * Delete all local data (Settings, issue #380 phase 1a: one tested method instead of calls in the screen). In this order:
 * <ol>
 * <li>the history in use ({@link Analyzer#deleteAll}: every table in one transaction, waiting for a running analysis; when it
 * fails nothing was deleted and nothing below runs),</li>
 * <li>the copies made before updates ({@link BeforeUpdate#deleteAll}; the copies in the person's backup folder stay, #242),</li>
 * <li>the sleep sound clips ({@link Sounds}): the demo's folder always (made up sounds), the real folder when the real history
 * was the one deleted. In demo mode the real history is not touched, so neither are its clips (folders never crossed). A file
 * that cannot be deleted is removed by the next sweep, since no row names it any more.</li>
 * </ol>
 * The caller holds the ring lock (no sync writes meanwhile). Phase 1b stops a running listening service here first.
 */
public final class DeleteAll {
    private DeleteAll() {}

    public static void run(Context c) {
        Db db = Db.get(c);
        boolean demo = Db.DEMO.equals(db.fileName);
        Analyzer.deleteAll(db);
        BeforeUpdate.deleteAll(c);
        Sounds.deleteTree(Sounds.folder(c, true));
        if (!demo) Sounds.deleteTree(Sounds.folder(c, false));
    }
}
