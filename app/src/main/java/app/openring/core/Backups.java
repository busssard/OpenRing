package app.openring.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of the automatic backups (issue #242), kept apart from Android so the JVM tests cover them: when the
 * first start of a new version copies the history, how the copies are named, which of them may go, when a folder copy
 * is due, and every sentence the screens show about them.
 */
public final class Backups {
    private Backups() {}

    // ------------------------------------------------------------------ phase 1: a copy before an update

    /** What the first open of the history in a process does: nothing, only note the version, or copy first. */
    public static final int SAME = 0, NOTE = 1, COPY = 2;

    /**
     * {@code noted}: the version code noted at the last start (0 for none, also when updating from a version that
     * noted nothing yet), {@code current}: this version's code (0 when it cannot be read), {@code fileExists}: the real
     * history file is there. Another version with a history file is an update and is copied first; without a file
     * (a new installation, a history deleted) the version is only noted.
     */
    public static int beforeUpdate(int noted, int current, boolean fileExists) {
        if (current <= 0 || noted == current) return SAME;
        return fileExists ? COPY : NOTE;
    }

    /** Room that must stay free on the phone beyond a copy's size. */
    public static final long MARGIN = 50L * 1024 * 1024;

    /** A copy of {@code size} bytes fits when {@code free} leaves {@link #MARGIN} after it. */
    public static boolean enoughSpace(long free, long size) {
        return free >= size + MARGIN;
    }

    /** States of a copy on the phone: being written, complete but not checked yet, checked, failed its check. */
    public static final String PARTIAL = "partial", RAW = "raw", CHECKED = "db", BAD = "bad";

    private static final Pattern BEFORE = Pattern.compile("before-(\\d+)-([0-9A-Za-z.]+)\\.(partial|raw|db|bad)");
    private static final Pattern BACK = Pattern.compile("back-(\\d+)\\.(partial|db)");

    /** The file name of the copy made before the update to {@code versionName} (code {@code code}). */
    public static String copyName(int code, String versionName, String state) {
        return "before-" + code + "-" + safe(versionName) + "." + state;
    }

    /** The file name of the copy of the history made before going back to a copy, at {@code unix} seconds. */
    public static String backName(long unix, String state) {
        return "back-" + unix + "." + state;
    }

    static String safe(String v) {
        StringBuilder b = new StringBuilder();
        if (v != null) for (char ch : v.toCharArray()) if (ch < 128 && (Character.isLetterOrDigit(ch) || ch == '.')) b.append(ch);
        return b.length() == 0 ? "unknown" : b.toString();
    }

    /** One copy on the phone, from its file name. */
    public static final class Copy {
        /** The file name (the main file; its log, if any, is the same name with {@code -wal}). */
        public final String file;
        /** True for a copy made before going back; false for one made before an update. */
        public final boolean back;
        /** The version code the update went to; for a copy before going back the time it was made (unix seconds). */
        public final long code;
        /** The version the update went to, null for a copy before going back. */
        public final String version;
        public final String state;

        Copy(String file, boolean back, long code, String version, String state) {
            this.file = file;
            this.back = back;
            this.code = code;
            this.version = version;
            this.state = state;
        }

        /** The same copy in another state (after its check). */
        public String named(String newState) {
            return back ? backName(code, newState) : copyName((int) code, version, newState);
        }
    }

    /** The copy a file name stands for, or null for any other file (also a copy's {@code -wal} log). */
    public static Copy parse(String file) {
        if (file == null) return null;
        Matcher m = BEFORE.matcher(file);
        try {
            if (m.matches()) return new Copy(file, false, Integer.parseInt(m.group(1)), m.group(2), m.group(3));
            m = BACK.matcher(file);
            if (m.matches()) return new Copy(file, true, Long.parseLong(m.group(1)), null, m.group(2));
        } catch (NumberFormatException e) {
            return null; // a number too long for a version: not one of ours
        }
        return null;
    }

    /** The copies among {@code files}, newest first (higher version, later time). */
    public static List<Copy> copies(Collection<String> files, boolean back) {
        List<Copy> out = new ArrayList<Copy>();
        for (String f : files) {
            Copy c = parse(f);
            if (c != null && c.back == back) out.add(c);
        }
        Collections.sort(out, new java.util.Comparator<Copy>() {
            @Override
            public int compare(Copy a, Copy b) {
                return Long.compare(b.code, a.code);
            }
        });
        return out;
    }

    /** Copies before updates kept on the phone. */
    public static final int PHONE_KEEP = 2;

    /**
     * The phone copies that may be deleted now. Before updates: the {@link #PHONE_KEEP} newest checked copies stay,
     * and an older copy (checked or failed) goes only once that many newer ones passed their check; a copy not checked
     * yet or being written is never deleted here. Before going back: the newest checked one stays, older ones go once a
     * newer one is checked.
     */
    public static List<String> phoneToDelete(Collection<String> files) {
        List<String> out = new ArrayList<String>();
        for (boolean back : new boolean[] {false, true}) {
            int keep = back ? 1 : PHONE_KEEP, passed = 0;
            for (Copy c : copies(files, back)) {
                if (passed >= keep && (CHECKED.equals(c.state) || BAD.equals(c.state))) out.add(c.file);
                else if (CHECKED.equals(c.state)) passed++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ phase 2: copies in a folder

    /** The job runs about once a day while the phone charges; Android picks the moment in the last 6 hours. */
    public static final long JOB_INTERVAL_MS = 24 * 60 * 60 * 1000L, JOB_FLEX_MS = 6 * 60 * 60 * 1000L;

    /** The automatic backup job as this version wants it (one fixed job whatever the interval, see {@link #due}). */
    public static BackgroundJob.Spec jobSpec(String service) {
        return new BackgroundJob.Spec(service, true, JOB_INTERVAL_MS, JOB_FLEX_MS, true, true, true);
    }

    /** How often (days) and how many copies to keep: the choices in Settings; the middle ones are the defaults. */
    public static final int[] DAYS = {1, 7, 30}, KEEP = {3, 5, 10};
    public static final int DEFAULT_DAYS = 7, DEFAULT_KEEP = 5;
    public static final String[] DAY_NAMES = {"Every day", "Every week", "Every month"};

    public static boolean validDays(int d) {
        for (int x : DAYS) if (x == d) return true;
        return false;
    }

    public static boolean validKeep(int k) {
        for (int x : KEEP) if (x == k) return true;
        return false;
    }

    /**
     * Whether a copy is due at {@code now} (unix seconds) with the last good regular copy at {@code lastGood} (0 for
     * none) and a copy every {@code days} days. The job runs about daily, at a moment Android picks within 6 hours, so
     * a copy counts as due 6 hours before the full interval: a weekly copy then does not slip a day each week. A last
     * copy that lies after now (the phone clock was set back) does not hold off copies until the clock catches up.
     */
    public static boolean due(long now, long lastGood, int days) {
        if (lastGood <= 0 || lastGood > now) return true;
        return now - lastGood >= days * 86400L - JOB_FLEX_MS / 1000;
    }

    public static final String FOLDER_PREFIX = "openring-backup-", ZIP = ".zip", PART = ".partial";
    private static final Pattern FOLDER = Pattern.compile("openring-backup-\\d{4}-\\d{2}-\\d{2}-\\d{4}(-before-[0-9A-Za-z.]+)?\\.zip");

    /** The folder file of a copy made at {@code t}: {@code openring-backup-2026-10-29-0312.zip}, with {@code -before-1.42} for an update copy. */
    public static String folderName(LocalDateTime t, String beforeVersion) {
        return FOLDER_PREFIX + t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.US))
                + (beforeVersion == null ? "" : "-before-" + safe(beforeVersion)) + ZIP;
    }

    /** A complete copy by its name (the app's own files are also listed in its settings, see {@link #folderToDelete}). */
    public static boolean folderFile(String name) {
        return name != null && FOLDER.matcher(name).matches();
    }

    /**
     * Which files in the folder to delete after {@code newFile} was written, read back and renamed: of the files that
     * match the name pattern and are listed as written by the app ({@code listed}), the newest {@code keep} complete
     * ones stay, {@code newFile} always among them; listed {@code .partial} files left by a stop go too. Nothing complete
     * is deleted unless {@code newFile} is there. Other files are never touched, also when their name looks like ours.
     */
    public static List<String> folderToDelete(Collection<String> present, Collection<String> listed, String newFile, int keep) {
        Set<String> mine = new HashSet<String>(listed);
        List<String> out = new ArrayList<String>();
        List<String> complete = new ArrayList<String>();
        for (String n : present) {
            if (n == null) continue;
            if (n.endsWith(PART)) {
                String base = n.substring(0, n.length() - PART.length());
                if (folderFile(base) && mine.contains(base) && !base.equals(newFile)) out.add(n);
            } else if (folderFile(n) && mine.contains(n) && !n.equals(newFile)) {
                complete.add(n);
            }
        }
        if (newFile == null || !present.contains(newFile)) return out;
        Collections.sort(complete, Collections.reverseOrder());
        for (int i = Math.max(0, keep - 1); i < complete.size(); i++) out.add(complete.get(i));
        return out;
    }

    // ------------------------------------------------------------------ why a copy failed

    /** Why the last folder copy failed (stored in the settings, shown in plain words by {@link #reason}). */
    public static final String GONE = "gone", FULL = "full", CHECK = "check", SPACE = "space", WRITE = "write";

    public static String reason(String code) {
        if (GONE.equals(code)) return "the folder is gone or Open Ring may no longer use it";
        if (FULL.equals(code)) return "the folder is full";
        if (CHECK.equals(code)) return "the copy did not pass its check";
        if (SPACE.equals(code)) return "not enough free space on this phone for the copy";
        return "the file could not be written";
    }

    /** A failure text that means a full disk (Android's ENOSPC, SQLite's full database). */
    public static boolean noSpace(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.US);
        return t.contains("enospc") || t.contains("no space left") || t.contains("sqlite_full") || t.contains("database or disk is full");
    }

    // ------------------------------------------------------------------ texts

    public static final String TITLE = "Automatic backups";
    public static final String OFF_TEXT = "Before each update Open Ring keeps a copy of your history on this phone. "
            + "A folder you choose also gets regular copies, which stay when the app is removed.";
    public static final String CHOOSE = "Choose a folder", CHOOSE_OTHER = "Choose another folder", CHOOSE_AGAIN = "Choose the folder again";
    public static final String HOW_OFTEN = "How often?", HOW_MANY = "Copies to keep";
    public static final String ALSO_UPDATE = "A copy is also made before every update.";
    public static final String STOP = "Stop automatic backups";
    public static final String STOPPED = "Automatic backups stopped. The copies in the folder stay.";
    public static final String PRIVATE = "The copies hold your ring key and all your data. Pick a folder that only you can reach.";
    public static final String NONE_YET = "No copy yet. The first one is made the next time the phone charges.";
    public static final String GO_BACK = "Go back to this copy";
    public static final String GO_BACK_TITLE = "Go back to this copy?";
    public static final String WENT_BACK = "Your history is back as it was in this copy";
    public static final String DEMO = "Demo: an example, nothing is saved.";
    public static final String NOT_IN_DEMO = "Not in demo mode.";
    public static final String NOTICE_SPACE = "No copy was made before this update: not enough free space.";
    public static final String NOTICE_OTHER = "No copy was made before this update. Details are in Data check.";
    public static final String COPY_EXPLAIN = "The date is when the copy was made, the size is the space it takes on this phone. "
            + "The two newest copies stay, older ones are deleted once newer ones passed their check.";
    public static final String FOLDER_PICK_FAILED = "Open Ring may not use this folder. Choose another one.";

    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH),
            DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    public static String day(long unix, ZoneId z) {
        return Instant.ofEpochSecond(unix).atZone(z).format(DAY);
    }

    public static String dayTime(long unix, ZoneId z) {
        return Instant.ofEpochSecond(unix).atZone(z).format(DAY_TIME);
    }

    /** Megabytes, rounded, at least 1 for a file that is not empty. */
    public static String mb(long bytes) {
        long m = Math.round(bytes / (1024.0 * 1024.0));
        if (m == 0 && bytes > 0) m = 1;
        return m + " MB";
    }

    /** "Copy from before the update to 1.42 (29 Oct, 210 MB)". */
    public static String copyLine(String version, long unix, long bytes, ZoneId z) {
        return "Copy from before the update to " + version + " (" + day(unix, z) + ", " + mb(bytes) + ")";
    }

    /** "Copy from before going back (30 Oct, 212 MB)". */
    public static String backLine(long unix, long bytes, ZoneId z) {
        return "Copy from before going back (" + day(unix, z) + ", " + mb(bytes) + ")";
    }

    /** A copy that failed its check: listed, but it cannot be used. */
    public static String badLine(String version, long unix, ZoneId z) {
        return "Copy from before the update to " + version + " (" + day(unix, z) + ") did not pass its check and cannot be used.";
    }

    /** The question before going back to a copy made at {@code unix}. */
    public static String goBackText(long unix, ZoneId z) {
        return "Your history is replaced by this copy. What was synced since " + day(unix, z) + " is kept as a copy of its own.";
    }

    /** The status line of the folder copies. {@code error}: a reason code, null when the last try worked. */
    public static String status(long lastOk, long bytes, int kept, String error, ZoneId z) {
        if (error != null) return "The last copy failed: " + reason(error) + ".";
        if (lastOk <= 0) return NONE_YET;
        return "Last copy: " + dayTime(lastOk, z) + ", " + mb(bytes) + ", " + kept + " kept";
    }

    /** What the numbers of {@link #status} mean. */
    public static String statusExplain(long bytes, int kept) {
        return mb(bytes) + " is the size of the last copy, " + kept + " kept is how many copies are in the folder now. "
                + "Copies are made while the phone charges.";
    }

    /** "every week", for the data check. */
    public static String every(int days) {
        return days == 1 ? "every day" : days == 30 ? "every month" : days == 7 ? "every week" : "every " + days + " days";
    }

    /** The data check's line about the copies before updates (counts and dates only). */
    public static String checkCopies(List<String[]> copies, String lastNote) {
        StringBuilder b = new StringBuilder("Copies before updates: ");
        if (copies.isEmpty()) b.append("none");
        for (int i = 0; i < copies.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(copies.get(i)[0]).append(" (").append(copies.get(i)[1]).append(')');
        }
        if (lastNote != null) b.append(". Last update: ").append(lastNote);
        return b.toString();
    }

    /** The data check's line about the folder copies. */
    public static String checkAuto(boolean on, int days, int keep, long lastOk, long bytes, long lastTry, String error, ZoneId z) {
        if (!on) return "Automatic backups: off";
        StringBuilder b = new StringBuilder("Automatic backups: ").append(every(days)).append(", keep ").append(keep);
        b.append(lastOk > 0 ? ", last copy " + dayTime(lastOk, z) + " (" + mb(bytes) + ")" : ", no copy yet");
        if (lastTry > 0) b.append(error == null ? ", last try ok" : ", last try failed: " + reason(error));
        return b.toString();
    }
}
