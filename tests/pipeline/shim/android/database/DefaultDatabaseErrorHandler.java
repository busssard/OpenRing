package android.database;
import android.database.sqlite.SQLiteDatabase;
/**
 * Like Android's default (issue #255): on a damaged file it closes the database and deletes the file
 * with its -journal, -shm and -wal files, so the next open creates an empty one.
 */
public final class DefaultDatabaseErrorHandler implements DatabaseErrorHandler {
    @Override
    public void onCorruption(SQLiteDatabase db) {
        try { db.close(); } catch (RuntimeException ignored) {}
        String p = db.getPath();
        for (String s : new String[] {"", "-journal", "-shm", "-wal"}) new java.io.File(p + s).delete();
    }
}
