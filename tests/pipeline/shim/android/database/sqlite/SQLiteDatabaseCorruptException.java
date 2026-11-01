package android.database.sqlite;
/** Like Android: what a query or statement throws on a damaged page (issue #255). */
public class SQLiteDatabaseCorruptException extends RuntimeException {
    public SQLiteDatabaseCorruptException(Throwable cause) { super(cause); }
}
