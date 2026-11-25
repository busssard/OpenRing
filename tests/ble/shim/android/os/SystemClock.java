package android.os;

import java.util.ArrayList;
import java.util.List;

/** Stand in for the JVM test of ble/Gatt: sleeps are written down, not slept. */
public final class SystemClock {
    public static List<String> calls = new ArrayList<String>();

    public static void sleep(long ms) {
        calls.add("sleep " + ms);
    }
}
