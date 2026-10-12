package android.database.sqlite;
import android.content.ContentValues;
import android.database.Cursor;
import java.sql.*;
/** JDBC backed stand in that binds rawQuery string args as text, like Android does. */
public class SQLiteDatabase {
    public static final int CONFLICT_REPLACE = 5;
    public final Connection conn;
    int depth; boolean ok;
    SQLiteDatabase(Connection c) { conn = c; }
    static RuntimeException wrap(Exception e) { return new RuntimeException(e); }
    public void execSQL(String sql) { try (Statement s = conn.createStatement()) { s.execute(sql); } catch (SQLException e) { throw wrap(e); } }
    public void execSQL(String sql, Object[] args) {
        try (PreparedStatement p = conn.prepareStatement(sql)) { bind(p, args); p.execute(); } catch (SQLException e) { throw wrap(e); }
    }
    static void bind(PreparedStatement p, Object[] args) throws SQLException {
        for (int i = 0; args != null && i < args.length; i++) p.setObject(i + 1, args[i]);
    }
    public Cursor rawQuery(String sql, String[] args) {
        try { PreparedStatement p = conn.prepareStatement(sql);
            for (int i = 0; args != null && i < args.length; i++) p.setString(i + 1, args[i]);
            Cursor c = new Cursor(p.executeQuery()); p.close(); return c;
        } catch (SQLException e) { throw wrap(e); }
    }
    public SQLiteStatement compileStatement(String sql) { try { return new SQLiteStatement(conn.prepareStatement(sql)); } catch (SQLException e) { throw wrap(e); } }
    public long insertWithOnConflict(String table, String nullHack, ContentValues cv, int conflict) {
        StringBuilder cols = new StringBuilder(), q = new StringBuilder();
        for (String k : cv.map.keySet()) { if (cols.length() > 0) { cols.append(','); q.append(','); } cols.append(k); q.append('?'); }
        execSQL("INSERT OR REPLACE INTO " + table + "(" + cols + ") VALUES (" + q + ")", cv.map.values().toArray());
        return 1;
    }
    public void beginTransaction() { try { if (depth++ == 0) { conn.setAutoCommit(false); ok = false; } } catch (SQLException e) { throw wrap(e); } }
    public void setTransactionSuccessful() { ok = true; }
    public void endTransaction() {
        try { if (--depth == 0) { if (ok) conn.commit(); else conn.rollback(); conn.setAutoCommit(true); } } catch (SQLException e) { throw wrap(e); }
    }
}
