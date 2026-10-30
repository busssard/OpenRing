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
    /** The app's private files (issue #242: the copies before an update live in files/before-update). */
    public java.io.File getFilesDir() { java.io.File f = new java.io.File(dir, "files"); f.mkdirs(); return f; }
    /** The installed version the tests set; 0 means unknown, so the copy before an update is not looked at (issue #242). */
    public static int versionCode = 0;
    public static String versionName = "test";
    /** The tests make the package manager fail, as it could on a phone. */
    public static boolean packageFails;
    public String getPackageName() { return "app.openring"; }
    public android.content.pm.PackageManager getPackageManager() { return new android.content.pm.PackageManager(); }
}
