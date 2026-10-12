package app.openring.core;

import java.util.Arrays;

/** One history event: tag, ring timestamp in deciseconds (per boot counter) and raw body. */
public final class RingEvent {
    public final int tag;
    public final long ds;
    public final byte[] body;

    public RingEvent(int tag, long ds, byte[] body) {
        this.tag = tag & 0xff;
        this.ds = ds;
        this.body = body == null ? new byte[0] : body;
    }

    /** Build from a history event packet (tag >= 0x41): 4 byte LE timestamp, then the body. */
    public static RingEvent fromPacket(Packet p) {
        long ts = p.payload.length >= 4 ? Protocol.Le.u32(p.payload, 0) : 0;
        byte[] body = p.payload.length > 4 ? Arrays.copyOfRange(p.payload, 4, p.payload.length) : new byte[0];
        return new RingEvent(p.tag, ts, body);
    }

    @Override
    public String toString() {
        return String.format("%s@%d[%s]", EventCodec.name(tag), ds, Hex.encode(body));
    }
}
