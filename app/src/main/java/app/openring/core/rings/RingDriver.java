package app.openring.core.rings;

import java.util.List;

/**
 * What a driver for another ring brand provides (issue #418, for phase 2 on; nothing implements it yet). A driver is pure
 * Java and knows only bytes: the Bluetooth session, the lock and the database stay outside it. It follows the order of the
 * watch sync ({@code ble/WatchSync}): store the raw message, then decode, then acknowledge.
 *
 * <p>The rules every driver and the session around it keep:
 * <ul>
 *   <li><b>Store before acknowledge.</b> Each message from the ring is stored as a raw row first (as it came, never changed
 *   or deleted). Only once that row is committed may {@link #ack} be sent: some rings (RingConn) move their own cursor on
 *   each acknowledgement, so a message acknowledged but not stored could never come back.</li>
 *   <li><b>Every write passes {@link Forbidden}.</b> The session gets requests and acknowledgements only through
 *   {@link Forbidden#requests} and {@link Forbidden#ack}, so a factory reset or wipe can never reach the ring.</li>
 *   <li><b>Only the chosen ring.</b> A driver is used only for the address the person chose in setup, never for a device
 *   found by name or family ({@link RingFamily} only names a family).</li>
 * </ul>
 */
public interface RingDriver {
    /** The family this driver speaks to; {@link Forbidden} checks its writes by it. */
    RingFamily family();

    /** What to ask the ring next, starting after {@code cursor} (a day, an index, whatever the family counts in). */
    List<byte[]> requests(long cursor);

    /** The raw rows one message from the ring becomes; empty when it is not one to keep (an answer to a setting). */
    List<Row> decode(byte[] message);

    /**
     * The acknowledgement of {@code message}, or null when the family needs none. The session sends it only after the
     * message's raw row is committed.
     */
    byte[] ack(byte[] message);

    /** Where the next sync starts, after the messages decoded so far were stored. */
    long nextCursor();

    /** One raw row: the kind of message and its bytes exactly as they came. */
    final class Row {
        public final String kind;
        public final byte[] bytes;

        public Row(String kind, byte[] bytes) {
            this.kind = kind;
            this.bytes = bytes.clone();
        }
    }
}
