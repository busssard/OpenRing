package app.openring.core.rings;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which protocol family a device belongs to, from what the Device check sees: its advertised name, its service UUIDs
 * (advertised, or listed once connected) and the maker name it reads (issue #418, phase 1). It only <b>names</b> a family
 * for the screen and the report: nothing ever picks, pairs or syncs a device because of it (a QRing ring has no lock, so a
 * neighbour's ring looks the same as one's own; phase 2 pairs only by the address chosen in setup).
 *
 * <p>The facts come from the Driver hunt on issue #418 and are written down in docs/ARCHITECTURE.md ("Other rings"), with
 * their sources. Only names and UUIDs are used here, in our own words; no code or table was copied.
 *
 * <p>Withings is checked first and is the one rule for "is this a Withings watch", used by the check's list
 * ({@code WatchCheck.likelyWatch}) and by Watch setup ({@code WatchCheck.setupName}).
 */
public enum RingFamily {
    WITHINGS("Withings", "a Withings watch"),
    QRING("QRing family (Colmi, Yawell, KSIX)", "a ring of the QRing family (Colmi, Yawell, KSIX)"),
    RINGCONN("RingConn", "a RingConn ring"),
    ULTRAHUMAN("Ultrahuman", "an Ultrahuman ring"),
    SAMSUNG("Samsung Galaxy Ring", "a Samsung Galaxy Ring"),
    ZEPP("Zepp (Amazfit)", "an Amazfit ring (Zepp)"),
    LEFUN("Lefun family", "a ring of the Lefun family"),
    VIATOM("Viatom (Wellue O2Ring)", "a Viatom or Wellue ring (O2Ring)"),
    UNKNOWN("not known", null);

    /** The family as the report names it ("Looks like: ..."). */
    public final String title;
    /** "a ring of the QRing family (Colmi, Yawell, KSIX)": the words after "This looks like"; null for {@link #UNKNOWN}. */
    public final String what;

    RingFamily(String title, String what) {
        this.title = title;
        this.what = what;
    }

    /** The issue where reports of other rings are collected. */
    public static final int ISSUE = 418;

    /** Words in a name that mean a Withings watch (anywhere in the name, any case). */
    static final String[] WITHINGS_WORDS = {"withings", "steel", "activit", "scanwatch", "hwa0"};

    // service UUIDs (lower case) and their parts, from the Driver hunt
    /** QRing's main service (a Nordic UART style service with its own base). */
    public static final String QRING_SERVICE = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e";
    /** QRing's second service, for the large transfers (sleep, blood oxygen). */
    public static final String QRING_BIG = "de5bf728-d711-4e47-af26-65e3012a5dc7";
    public static final String RINGCONN_SERVICE = "8327ad99-2d87-4a22-a8ce-6dd7971c0437";
    /** Ultrahuman's two services; only their first 8 digits are published, so those are matched. */
    static final String[] ULTRAHUMAN_PREFIX = {"86f65000-", "86f61000-"};
    public static final String SAMSUNG_SERVICE = "00001b1b-0000-1000-8000-00805f9b34fb";
    public static final String LEFUN_SERVICE = "000018d0-0000-1000-8000-00805f9b34fb";
    public static final String VIATOM_SERVICE = "14839ac4-7d7e-415c-9a42-167340cf2339";
    /** The maker name RingConn rings give. */
    static final String RINGCONN_MAKER = "jz_tech";

    /** Colmi's names: R, two digits and an underscore ("R02_341C"). */
    private static final Pattern QRING_NAME = Pattern.compile("^R\\d\\d_.*");

    /** True when the name means a Withings watch: one of the words anywhere in it, in any case. */
    public static boolean withingsName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String w : WITHINGS_WORDS) if (n.contains(w)) return true;
        return false;
    }

    /** True for a UUID with "WITH" in ASCII where the public notes put it (5749-5448). */
    public static boolean withingsService(String uuid) {
        return uuid != null && uuid.toLowerCase(Locale.ROOT).contains("-5749-5448-");
    }

    /** True when the name or one of the services means a Withings watch. */
    public static boolean withings(String name, List<String> services) {
        if (withingsName(name)) return true;
        for (String s : nz(services)) if (withingsService(s)) return true;
        return false;
    }

    /**
     * The family of a device: Withings first (so a watch is found as before), then the services, then the maker name,
     * then the name. {@code services} and {@code maker} may be null.
     */
    public static RingFamily of(String name, List<String> services, String maker) {
        if (withings(name, services)) return WITHINGS;
        for (String raw : nz(services)) {
            if (raw == null) continue;
            String s = raw.toLowerCase(Locale.ROOT);
            if (s.equals(QRING_SERVICE) || s.equals(QRING_BIG)) return QRING;
            if (s.equals(RINGCONN_SERVICE)) return RINGCONN;
            for (String p : ULTRAHUMAN_PREFIX) if (s.startsWith(p)) return ULTRAHUMAN;
            if (s.equals(SAMSUNG_SERVICE)) return SAMSUNG;
            if (s.equals(LEFUN_SERVICE)) return LEFUN;
            if (s.equals(VIATOM_SERVICE)) return VIATOM;
        }
        if (maker != null && maker.toLowerCase(Locale.ROOT).contains(RINGCONN_MAKER)) return RINGCONN;
        String n = name == null ? "" : name.trim();
        String low = n.toLowerCase(Locale.ROOT);
        if (QRING_NAME.matcher(n).matches()) return QRING;
        if (n.startsWith("UH_") || low.contains("ultrahuman")) return ULTRAHUMAN;
        if (low.contains("ringconn")) return RINGCONN;
        if (low.contains("galaxy ring")) return SAMSUNG;
        if (low.contains("helio")) return ZEPP;
        if (low.contains("o2ring")) return VIATOM;
        return UNKNOWN;
    }

    /** True for every family except Withings and unknown: the rings Open Ring cannot read yet. */
    public boolean otherRing() {
        return this != WITHINGS && this != UNKNOWN;
    }

    /**
     * The one line the Device check shows for a device of this family once it was checked: what it looks like and what
     * to do with the report. Null for Withings, which has its own lines (the watch check of issue #251).
     */
    public String line() {
        switch (this) {
            case WITHINGS:
                return null;
            case UNKNOWN:
                return "Open Ring does not know this device. If it is a ring or watch, copy the report and add it to issue " + ISSUE
                        + " on GitHub, so we can learn how it talks.";
            case ZEPP:
                return "This looks like " + what + ". Open Ring cannot read it: its key comes only from Zepp's servers, and Open Ring "
                        + "never goes online. The report still helps, add it to issue " + ISSUE + " on GitHub.";
            default:
                return "This looks like " + what + ". Open Ring cannot read it yet. Copy the report and add it to issue " + ISSUE
                        + " on GitHub, so we can build it.";
        }
    }

    /** "Looks like a ring of the QRing family (Colmi, Yawell, KSIX)", the short line under a row of the scan list; null for unknown. */
    public String rowLine() {
        return what == null ? null : "Looks like " + what;
    }

    private static List<String> nz(List<String> l) {
        return l == null ? Collections.<String>emptyList() : l;
    }
}
