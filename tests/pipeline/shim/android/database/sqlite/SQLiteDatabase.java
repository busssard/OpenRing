package android.database.sqlite;
import android.content.ContentValues;
import android.database.Cursor;
import java.sql.*;
/** JDBC backed stand in that binds rawQuery string args as text, like Android does. */
public class SQLiteDatabase {
    public static final int CONFLICT_REPLACE = 5;
    public final Connection conn;
    volatile int depth;
    public static final int OPEN_READWRITE = 0, OPEN_READONLY = 1, NO_LOCALIZED_COLLATORS = 16;
    public interface CursorFactory {}
    /** The file and the handler Android calls on a damaged page (issue #255); null means Android's default, which deletes the file. */
    final String path;
    final android.database.DatabaseErrorHandler handler;
    SQLiteDatabase(Connection c, String path, android.database.DatabaseErrorHandler h) {
        conn = c; this.path = path; handler = h != null ? h : new android.database.DefaultDatabaseErrorHandler();
    }
    public String getPath() { return path; }
    public boolean isOpen() { try { return !conn.isClosed(); } catch (SQLException e) { return false; } }
    /** SQLITE_CORRUPT (11) or SQLITE_NOTADB (26), with or without extended result codes. */
    static boolean corrupt(SQLException e) {
        int code = e.getErrorCode() & 0xff;
        String m = String.valueOf(e.getMessage());
        return code == 11 || code == 26 || m.contains("SQLITE_CORRUPT") || m.contains("SQLITE_NOTADB");
    }
    /** Like Android's SQLiteQuery and SQLiteStatement: a damaged page calls the handler first, then the error is thrown. */
    RuntimeException fail(SQLException e) {
        if (!corrupt(e)) return wrap(e);
        handler.onCorruption(this);
        return new SQLiteDatabaseCorruptException(e);
    }
    /** Opens an existing file on its own (no helper, no upgrade); fails like Android when it is missing. */
    public static SQLiteDatabase openDatabase(String path, CursorFactory f, int flags, android.database.DatabaseErrorHandler h) {
        if (!new java.io.File(path).isFile()) throw new RuntimeException("unable to open database file: " + path);
        try {
            java.util.Properties p = new java.util.Properties();
            if ((flags & OPEN_READONLY) != 0) p.setProperty("open_mode", "1");
            return new SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:" + path, p), path, h);
        } catch (SQLException e) { throw wrap(e); }
    }
    public int getVersion() {
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); return r.getInt(1); }
        catch (SQLException e) { throw fail(e); }
    }
    public void close() { try { conn.close(); } catch (SQLException e) { throw wrap(e); } }
    static RuntimeException wrap(Exception e) { return new RuntimeException(e); }
    /**
     * Android gives a transaction its own connection: a write from another thread waits until it ends (minutes during
     * Re analyse all), reads see the last commit. This one connection would let that write join the open transaction
     * and roll back with it, so a write from another thread fails here instead (issue #260).
     */
    volatile Thread owner;
    void mayWrite() {
        Thread t = owner;
        if (t != null && t != Thread.currentThread())
            throw new IllegalStateException("would wait on Android: a write from " + Thread.currentThread().getName() + " while " + t.getName() + " has a transaction open");
    }
    /** A statement that only reads (a query, a pragma that is not set), allowed from any thread like Android's read connections. */
    static boolean readOnly(String sql) {
        String q = sql.trim().toUpperCase(java.util.Locale.ROOT);
        return q.startsWith("SELECT") || q.startsWith("PRAGMA") && !q.contains("=");
    }
    public void execSQL(String sql) {
        if (!readOnly(sql)) mayWrite();
        try (Statement s = conn.createStatement()) { s.execute(sql); } catch (SQLException e) { throw fail(e); }
    }
    public void execSQL(String sql, Object[] args) {
        if (!readOnly(sql)) mayWrite();
        try (PreparedStatement p = conn.prepareStatement(sql)) { bind(p, args); p.execute(); } catch (SQLException e) { throw fail(e); }
    }
    static void bind(PreparedStatement p, Object[] args) throws SQLException {
        for (int i = 0; args != null && i < args.length; i++) p.setObject(i + 1, args[i]);
    }
    public int delete(String table, String where, String[] args) {
        mayWrite();
        try (PreparedStatement p = conn.prepareStatement("DELETE FROM " + table + (where == null ? "" : " WHERE " + where))) {
            for (int i = 0; args != null && i < args.length; i++) p.setString(i + 1, args[i]);
            return p.executeUpdate();
        } catch (SQLException e) { throw fail(e); }
    }
    public Cursor rawQuery(String sql, String[] args) {
        try { PreparedStatement p = conn.prepareStatement(sql);
            for (int i = 0; args != null && i < args.length; i++) p.setString(i + 1, args[i]);
            Cursor c = new Cursor(p.executeQuery()); p.close(); return c;
        } catch (SQLException e) { throw fail(e); }
    }
    public SQLiteStatement compileStatement(String sql) { try { return new SQLiteStatement(this, conn.prepareStatement(sql)); } catch (SQLException e) { throw fail(e); } }
    public long insertWithOnConflict(String table, String nullHack, ContentValues cv, int conflict) {
        StringBuilder cols = new StringBuilder(), q = new StringBuilder();
        for (String k : cv.map.keySet()) { if (cols.length() > 0) { cols.append(','); q.append(','); } cols.append(k); q.append('?'); }
        execSQL("INSERT OR REPLACE INTO " + table + "(" + cols + ") VALUES (" + q + ")", cv.map.values().toArray());
        return 1;
    }
    /** Like Android: each level marks its own success; the outermost commits only when it and every inner level did (issue #230). */
    final java.util.ArrayDeque<boolean[]> levels = new java.util.ArrayDeque<boolean[]>();
    boolean childFailed;
    public void beginTransaction() {
        mayWrite();
        try { if (depth++ == 0) { conn.setAutoCommit(false); childFailed = false; owner = Thread.currentThread(); } levels.push(new boolean[1]); } catch (SQLException e) { throw fail(e); }
    }
    public void setTransactionSuccessful() { mayWrite(); levels.peek()[0] = true; }
    public void endTransaction() {
        mayWrite();
        boolean marked = levels.pop()[0];
        try {
            if (--depth == 0) {
                try { if (marked && !childFailed) conn.commit(); else conn.rollback(); conn.setAutoCommit(true); } finally { owner = null; }
            }
            else if (!marked) childFailed = true;
        } catch (SQLException e) { throw fail(e); }
    }
}
