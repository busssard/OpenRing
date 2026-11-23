package app.openring.core.rings;

import java.util.ArrayList;
import java.util.List;

/**
 * The commands a ring driver must never send (issue #418): every byte array a {@link RingDriver} writes passes
 * {@link #check}, which refuses a destructive command. The facts come from the Driver hunt on issue #418:
 * <ul>
 *   <li>QRing (Colmi, Yawell, KSIX): 16 byte packets, the command in byte 0; command 0xff is the factory reset.</li>
 *   <li>Ultrahuman: the opcode in byte 0; opcode 0x98 is a reset that wipes the ring's data.</li>
 * </ul>
 * A family whose framing is not written down here (every other one, and unknown devices) gets nothing through at all: a
 * driver for it has to add its rules here first, with a test.
 */
public final class Forbidden {
    private Forbidden() {}

    /** QRing's factory reset, in byte 0 of a packet. */
    public static final int QRING_RESET = 0xff;
    /** Ultrahuman's reset that wipes the ring's data, in byte 0. */
    public static final int ULTRAHUMAN_WIPE = 0x98;

    /** A write that was refused; a driver that builds one has a bug, so it ends the sync. */
    public static final class Refused extends RuntimeException {
        public Refused(String why) {
            super(why);
        }
    }

    /** Null when {@code bytes} may be sent to a ring of {@code family}, otherwise why not (for the log and the tests). */
    public static String reason(RingFamily family, byte[] bytes) {
        if (family == null) return "no family";
        if (bytes == null || bytes.length == 0) return "nothing to send";
        int first = bytes[0] & 0xff;
        switch (family) {
            case QRING:
                return first == QRING_RESET ? "QRing factory reset (0xff)" : null;
            case ULTRAHUMAN:
                return first == ULTRAHUMAN_WIPE ? "Ultrahuman wipe (0x98)" : null;
            default:
                return "no rules for " + family.name() + " yet";
        }
    }

    /** True when {@code bytes} may be sent. */
    public static boolean allowed(RingFamily family, byte[] bytes) {
        return reason(family, bytes) == null;
    }

    /** Returns {@code bytes} unchanged when they may be sent; throws {@link Refused} otherwise. */
    public static byte[] check(RingFamily family, byte[] bytes) {
        String why = reason(family, bytes);
        if (why != null) throw new Refused(why);
        return bytes;
    }

    /** The driver's next requests from {@code cursor}, each one passed through {@link #check}: the only way a sync gets them. */
    public static List<byte[]> requests(RingDriver driver, long cursor) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (byte[] b : driver.requests(cursor)) out.add(check(driver.family(), b));
        return out;
    }

    /** The acknowledgement of {@code message}, passed through {@link #check}; null when the driver sends none. */
    public static byte[] ack(RingDriver driver, byte[] message) {
        byte[] a = driver.ack(message);
        return a == null ? null : check(driver.family(), a);
    }
}
