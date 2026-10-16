package android.database;
import android.database.sqlite.SQLiteDatabase;
/** Stand in for Android's corruption callback. */
public interface DatabaseErrorHandler {
    void onCorruption(SQLiteDatabase db);
}
