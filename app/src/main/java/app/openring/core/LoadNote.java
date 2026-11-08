package app.openring.core;

/**
 * The one line the data check keeps about a part of loading a day that failed and kept its fallback (issue #235),
 * for example {@code "day 2026-11-01, screen time: NumberFormatException at ScreenDays.java:182 (noted 2026-11-02 08:14)"}.
 * It holds the date, the part, the error's class and the first place in Open Ring's own code it came from, never the
 * error's message: a message can carry a value ("For input string 61.5"), and the data check stays safe to share. The
 * full message goes only to Android's log and the app's Log tab.
 */
public final class LoadNote {
    private LoadNote() {}

    static final String NOTED = " (noted ";

    /** The error's class and its first stack frame in {@code app.openring} code (file and line), without its message. */
    public static String error(Throwable e) {
        if (e == null) return "unknown error";
        String s = e.getClass().getSimpleName();
        if (s.isEmpty()) {
            // an anonymous class has no simple name: its binary name without the package, such as Analyzer$1
            String n = e.getClass().getName();
            s = n.substring(n.lastIndexOf('.') + 1);
        }
        for (StackTraceElement f : e.getStackTrace()) {
            if (f == null || f.getClassName() == null || !f.getClassName().startsWith("app.openring.")) continue;
            if (f.getFileName() == null) return s;
            return s + " at " + f.getFileName() + (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "");
        }
        return s;
    }

    /**
     * True when {@code text} has the form {@link #error} gives (a class name without its package, then optionally
     * {@code " at "}, a file and {@code ":"} a line), so it holds no message (issue #289). A text stored by an older
     * version as {@code e.toString()} starts with the full class name, which has dots, and never passes.
     */
    public static boolean safe(String text) {
        return text != null && text.matches("[A-Za-z0-9_$]+( at [^\\s:]+(:[0-9]+)?)?");
    }

    /** The line for a failed {@code part} while loading {@code date} (ISO), first noted at {@code when}. */
    public static String line(String date, String part, Throwable e, String when) {
        return "day " + date + ", " + part + ": " + error(e) + NOTED + when + ")";
    }

    /** The line without its time: two failures with the same key are the same one, the stored line is kept. */
    public static String key(String line) {
        if (line == null) return null;
        int i = line.lastIndexOf(NOTED);
        return i < 0 ? line : line.substring(0, i);
    }

    /** True when {@code fresh} needs writing over {@code stored}: a different date, part or error (not only a later time). */
    public static boolean differs(String stored, String fresh) {
        String a = key(stored), b = key(fresh);
        return a == null ? b != null : !a.equals(b);
    }

    /** True when a load of {@code date} where every part worked clears {@code stored}: it is about that date. */
    public static boolean clearedBy(String stored, String date) {
        return stored != null && date != null && stored.startsWith("day " + date + ", ");
    }
}
