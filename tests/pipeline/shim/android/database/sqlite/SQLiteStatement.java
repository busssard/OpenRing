package android.database.sqlite;
import java.sql.*;
public class SQLiteStatement {
    final PreparedStatement p;
    SQLiteStatement(PreparedStatement p) { this.p = p; }
    public void bindLong(int i, long v) { try { p.setLong(i, v); } catch (SQLException e) { throw new RuntimeException(e); } }
    public void bindBlob(int i, byte[] v) { try { p.setBytes(i, v); } catch (SQLException e) { throw new RuntimeException(e); } }
    /** -1 when the row was ignored by a conflict clause, as on Android. */
    public long executeInsert() {
        try { int n = p.executeUpdate(); if (n == 0) return -1;
            try (Statement s = p.getConnection().createStatement(); ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) { r.next(); return r.getLong(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
