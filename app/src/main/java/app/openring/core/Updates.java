package app.openring.core;

/**
 * Where "Check for updates" leads. A stable version opens the latest stable release; a nightly (its version name
 * ends in " nightly &lt;commit&gt;", see build_apk.sh) opens the list of all releases, where the nightly is at the top
 * and the stable release below it, so its user can pick either and usually takes the next nightly.
 */
public final class Updates {
    private Updates() {}

    public static final String LATEST = "https://github.com/busssard/OpenRing/releases/latest";
    public static final String ALL = "https://github.com/busssard/OpenRing/releases";

    /** True for a version name of a nightly build ("1.93 nightly abc1234"). */
    public static boolean nightly(String versionName) {
        return versionName != null && versionName.toLowerCase(java.util.Locale.ROOT).contains("nightly");
    }

    /** The page "Check for updates" opens for this installed version. */
    public static String url(String versionName) {
        return nightly(versionName) ? ALL : LATEST;
    }

    /** The line under the button. */
    public static String note(String versionName) {
        if (nightly(versionName)) {
            return "You have a nightly build (the newest changes, tested less). The button opens the list of all "
                    + "releases: the nightly at the top, the stable release below it. Installing either keeps your data.";
        }
        return "Opens the newest stable release in your browser, because this app has no internet access. "
                + "A higher version number there means an update; installing it keeps your data.";
    }
}
