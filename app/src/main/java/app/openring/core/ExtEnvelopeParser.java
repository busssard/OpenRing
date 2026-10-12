package app.openring.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reassembles ExtGetEvent envelope chunks (0x2f ext 0x43) into normal history event packets.
 * Each completed envelope is a bundle of {@code control | tag | ext_len | ts_varint | body}; the
 * first varint is absolute milliseconds, later ones are millisecond deltas. Port of open_oura
 * {@code ExtEventEnvelopeParser}.
 */
public final class ExtEnvelopeParser {
    private final ByteArrayOutputStream lenBuf = new ByteArrayOutputStream();
    private int expected = -1;
    private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

    public List<Packet> push(Packet packet) {
        List<Packet> out = new ArrayList<Packet>();
        if (packet.tag != 0x2f || packet.extTag() != 0x43) return out;
        byte[] data = packet.payload;
        int pos = 1;
        while (pos < data.length) {
            if (expected < 0) {
                int need = 2 - lenBuf.size();
                int take = Math.min(need, data.length - pos);
                lenBuf.write(data, pos, take);
                pos += take;
                if (lenBuf.size() < 2) continue;
                byte[] lb = lenBuf.toByteArray();
                int len = (lb[0] & 0xff) | ((lb[1] & 0xff) << 8);
                lenBuf.reset();
                expected = len;
                payload.reset();
                if (len == 0) expected = -1;
            }
            if (expected >= 0) {
                int need = expected - payload.size();
                int take = Math.min(need, data.length - pos);
                payload.write(data, pos, take);
                pos += take;
                if (payload.size() == expected) {
                    out.addAll(parseBundle(payload.toByteArray()));
                    payload.reset();
                    expected = -1;
                }
            }
        }
        return out;
    }

    static List<Packet> parseBundle(byte[] b) {
        List<Packet> out = new ArrayList<Packet>();
        int i = 0;
        long lastMs = -1;
        long lastTs = -1;
        while (i + 3 <= b.length) {
            int tag = b[i + 1] & 0xff;
            int extLen = b[i + 2] & 0xff;
            int start = i + 3;
            int end = start + extLen;
            if (tag < Protocol.HISTORY_EVENT_PREFIX || end > b.length) break;
            long value = 0;
            int used = -1;
            for (int k = 0; k < 10 && start + k < end; k++) {
                int x = b[start + k] & 0xff;
                value |= ((long) (x & 0x7f)) << (7 * k);
                if ((x & 0x80) == 0) {
                    used = k + 1;
                    break;
                }
            }
            if (used < 0) break;
            long ms = (lastMs >= 0 && value < 100_000_000L) ? lastMs + value : value;
            lastMs = ms;
            long ts = (ms / 100) & 0xffffffffL;
            if (lastTs >= 0 && value > 0 && ts <= lastTs) ts = lastTs + 1;
            lastTs = ts;
            int bodyLen = end - (start + used);
            byte[] p = new byte[4 + bodyLen];
            Protocol.Le.putU32(p, 0, ts);
            System.arraycopy(b, start + used, p, 4, bodyLen);
            out.add(new Packet(tag, p));
            i = end;
        }
        return out;
    }
}
