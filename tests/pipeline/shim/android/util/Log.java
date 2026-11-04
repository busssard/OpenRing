package android.util;
/** Android's log for the JVM tests: keeps nothing, so the test output stays readable. */
public class Log {
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter w = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}
