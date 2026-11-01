package android.database.sqlite;
import android.content.Context;
import java.sql.*;
public abstract class SQLiteOpenHelper {
    final Context ctx; final String name; final int version; SQLiteDatabase db;
    /** The handler given to the constructor, null for Android's default that deletes a damaged file (issue #255). */
    public final android.database.DatabaseErrorHandler errorHandler;
    public SQLiteOpenHelper(Context c, String name, Object factory, int version) { this(c, name, factory, version, null); }
    public SQLiteOpenHelper(Context c, String name, Object factory, int version, android.database.DatabaseErrorHandler h) {
        ctx = c; this.name = name; this.version = version; errorHandler = h;
    }
    public void setWriteAheadLoggingEnabled(boolean b) {}
    public abstract void onCreate(SQLiteDatabase db);
    public abstract void onUpgrade(SQLiteDatabase db, int o, int n);
    /** Like Android: without an override, opening a database from a newer app version fails. */
    public void onDowngrade(SQLiteDatabase db, int o, int n) {
        throw new RuntimeException("Can't downgrade database from version " + o + " to " + n);
    }
    public synchronized SQLiteDatabase getWritableDatabase() {
        if (db != null) return db;
        Connection c = null;
        try {
            String path = ctx.getDatabasePath(name).getPath();
            c = DriverManager.getConnection("jdbc:sqlite:" + path);
            SQLiteDatabase d = new SQLiteDatabase(c, path, errorHandler);
            // like Android: a damaged header calls the handler (the default deletes the file), the open then fails
            int v = d.getVersion();
            if (v == 0) onCreate(d); else if (v < version) onUpgrade(d, v, version); else if (v > version) onDowngrade(d, v, version);
            d.execSQL("PRAGMA user_version = " + version);
            db = d;
            return db;
        } catch (SQLException e) {
            closeQuietly(c);
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            // a failed open leaves nothing open behind, like Android
            closeQuietly(c);
            throw e;
        }
    }
    static void closeQuietly(Connection c) { if (c != null) try { c.close(); } catch (SQLException ignored) {} }
    public SQLiteDatabase getReadableDatabase() { return getWritableDatabase(); }
    public synchronized void close() { if (db != null) { try { db.conn.close(); } catch (SQLException e) { throw new RuntimeException(e); } db = null; } }
}
