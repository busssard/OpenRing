package app.openring.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One Oura protocol frame: {@code tag | length | payload}. All multi byte integers are little
 * endian. Extended operations ride under outer tag 0x2f with the first payload byte as the
 * extended op. Port of open_oura {@code oura-protocol/src/protocol.rs}.
 */
public final class Packet {
    public final int tag;
    public final byte[] payload;

    public Packet(int tag, byte[] payload) {
        this.tag = tag & 0xff;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public byte[] encode() {
        byte[] out = new byte[payload.length + 2];
        out[0] = (byte) tag;
        out[1] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 2, payload.length);
        return out;
    }

    /** Extended op tag (first payload byte) for 0x2f frames, or -1. */
    public int extTag() {
        if (tag == 0x2f && payload.length > 0) return payload[0] & 0xff;
        return -1;
    }

    public int u8(int i) {
        return i < payload.length ? payload[i] & 0xff : -1;
    }

    /** Lenient single frame parse; uses the remaining bytes if the declared length is off. */
    public static Packet parse(byte[] frame) {
        if (frame == null || frame.length < 2) return null;
        int tag = frame[0] & 0xff;
        int len = frame[1] & 0xff;
        byte[] payload;
        if (2 + len <= frame.length) {
            payload = Arrays.copyOfRange(frame, 2, 2 + len);
        } else {
            payload = Arrays.copyOfRange(frame, 2, frame.length);
        }
        return new Packet(tag, payload);
    }

    /**
     * Parse every complete packet concatenated into one notification (newer rings pack history
     * events this way).
     */
    public static List<Packet> parseMany(byte[] frame) {
        List<Packet> out = new ArrayList<Packet>();
        if (frame == null) return out;
        int i = 0;
        while (i + 2 <= frame.length) {
            int tag = frame[i] & 0xff;
            int len = frame[i + 1] & 0xff;
            int start = i + 2;
            int end = start + len;
            if (end > frame.length) break;
            out.add(new Packet(tag, Arrays.copyOfRange(frame, start, end)));
            i = end;
        }
        if (out.isEmpty()) {
            Packet p = parse(frame);
            if (p != null) out.add(p);
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format("{tag=0x%02x ext=%s payload=%s}", tag,
                extTag() >= 0 ? String.format("0x%02x", extTag()) : "-", Hex.encode(payload));
    }
}
