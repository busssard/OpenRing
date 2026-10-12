package android.database.sqlite;
import android.content.Context;
import java.sql.*;
public abstract class SQLiteOpenHelper {
    final Context ctx; final String name; final int version; SQLiteDatabase db;
    public SQLiteOpenHelper(Context c, String name, Object factory, int version) { ctx = c; this.name = name; this.version = version; }
    public void setWriteAheadLoggingEnabled(boolean b) {}
    public abstract void onCreate(SQLiteDatabase db);
    public abstract void onUpgrade(SQLiteDatabase db, int o, int n);
    public synchronized SQLiteDatabase getWritableDatabase() {
        if (db != null) return db;
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + ctx.getDatabasePath(name).getPath());
            db = new SQLiteDatabase(c);
            int v; try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); v = r.getInt(1); }
            if (v == 0) onCreate(db); else if (v < version) onUpgrade(db, v, version);
            db.execSQL("PRAGMA user_version = " + version);
            return db;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    public SQLiteDatabase getReadableDatabase() { return getWritableDatabase(); }
    public synchronized void close() { if (db != null) { try { db.conn.close(); } catch (SQLException e) { throw new RuntimeException(e); } db = null; } }
}
