package android.content.pm;
/** JVM stand in: answers with the version the test set on {@code Context} (issue #242). */
public class PackageManager {
    public static class NameNotFoundException extends Exception {}
    public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
        if (android.content.Context.packageFails) throw new NameNotFoundException();
        PackageInfo i = new PackageInfo();
        i.versionCode = android.content.Context.versionCode;
        i.versionName = android.content.Context.versionName;
        return i;
    }
}
