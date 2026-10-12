package android.content;
import java.util.HashMap;
import java.util.Map;
/** JVM stand in: only what the data layer touches. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    public final java.io.File dir;
    private final Map<String, SharedPreferences> prefs = new HashMap<String, SharedPreferences>();
    public Context(java.io.File dir) { this.dir = dir; }
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences p = prefs.get(name);
        if (p == null) { p = new SharedPreferences(); prefs.put(name, p); }
        return p;
    }
    public java.io.File getDatabasePath(String name) { return new java.io.File(dir, name); }
    public java.io.File getCacheDir() { return dir; }
}
