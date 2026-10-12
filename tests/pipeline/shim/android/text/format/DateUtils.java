package android.text.format;
public class DateUtils {
    public static final long MINUTE_IN_MILLIS = 60000;
    public static CharSequence getRelativeTimeSpanString(long t, long now, long res) { return ((now - t) / 60000) + " min ago"; }
}
