package app.openring.core.withings;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Withings message frame (issue #251, phase 1b), from public protocol notes and written anew (see
 * docs/ARCHITECTURE.md). A message is the byte {@code 01}, its type (2 bytes), the length of what follows (2 bytes) and
 * then parts, each its own type (2 bytes), length (2 bytes) and value. Everything is big endian. The watch's own
 * messages (not answers) carry {@code 0x40} or {@code 0x80} in the top byte of the type, so the type is read with
 * those two bits masked off. Messages travel in Bluetooth pieces and are put together by {@link Assembler}.
 */
public final class Frame {
    private Frame() {}

    /** The first byte of every message. */
    public static final int START = 0x01;
    /** Bytes before the parts: start, type, length. */
    public static final int HEADER = 5;
    /** Bytes before a part's value: type, length. */
    public static final int PART_HEADER = 4;
    /** The largest message this app puts together; anything longer is damaged and dropped. */
    public static final int MAX_MESSAGE = 65535 + HEADER;

    /** One part of a message. */
    public static final class Part {
        public final int type;
        public final byte[] value;

        public Part(int type, byte[] value) {
            this.type = type;
            this.value = value;
        }
    }

    /** The bytes of a part with {@code type} and {@code value}. */
    public static byte[] part(int type, byte[] value) {
        byte[] v = value == null ? new byte[0] : value;
        byte[] out = new byte[PART_HEADER + v.length];
        put16(out, 0, type);
        put16(out, 2, v.length);
        System.arraycopy(v, 0, out, PART_HEADER, v.length);
        return out;
    }

    /** A whole message of {@code type} with the given parts (each made by {@link #part}). */
    public static byte[] message(int type, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] b = body.toByteArray();
        if (b.length > 65535) throw new IllegalArgumentException("message too long");
        byte[] out = new byte[HEADER + b.length];
        out[0] = (byte) START;
        put16(out, 1, type);
        put16(out, 3, b.length);
        System.arraycopy(b, 0, out, HEADER, b.length);
        return out;
    }

    /** The type of a whole message, without the two top bits that mark the watch's own messages; -1 when too short. */
    public static int type(byte[] msg) {
        if (msg == null || msg.length < HEADER) return -1;
        return u16(msg, 1) & 0x3fff;
    }

    /**
     * True when {@code msg} is one whole message: the start byte and exactly as long as its header says. Every message the
     * {@link Assembler} completes is whole; the bytes it had to drop ({@link Assembler#take}) never are.
     */
    public static boolean whole(byte[] msg) {
        return msg != null && msg.length >= HEADER && (msg[0] & 0xff) == START && msg.length == HEADER + u16(msg, 3);
    }

    /** True when the watch started this message itself (a top bit of the type set), not as an answer. */
    public static boolean fromWatch(byte[] msg) {
        return msg != null && msg.length >= HEADER && (msg[1] & 0xc0) != 0;
    }

    /**
     * The parts of a whole message, in order. A part that claims more bytes than are left ends the list there (the
     * parts before it are kept), so a damaged message never throws.
     */
    public static List<Part> parts(byte[] msg) {
        List<Part> out = new ArrayList<Part>();
        if (msg == null || msg.length < HEADER) return out;
        int end = Math.min(msg.length, HEADER + u16(msg, 3));
        int i = HEADER;
        while (i + PART_HEADER <= end) {
            int t = u16(msg, i), n = u16(msg, i + 2);
            if (i + PART_HEADER + n > end) break;
            byte[] v = new byte[n];
            System.arraycopy(msg, i + PART_HEADER, v, 0, n);
            out.add(new Part(t, v));
            i += PART_HEADER + n;
        }
        return out;
    }

    /** True when the message holds a part of {@code type}. */
    public static boolean has(byte[] msg, int type) {
        for (Part p : parts(msg)) if (p.type == type) return true;
        return false;
    }

    /** The first part of {@code type}, or null. */
    public static Part first(byte[] msg, int type) {
        for (Part p : parts(msg)) if (p.type == type) return p;
        return null;
    }

    /** {@code msg} cut into Bluetooth pieces of at most {@code size} bytes (the link's MTU minus 3), in order. */
    public static List<byte[]> pieces(byte[] msg, int size) {
        if (size < 1) throw new IllegalArgumentException("piece size " + size);
        List<byte[]> out = new ArrayList<byte[]>();
        for (int i = 0; i < msg.length; i += size) {
            int n = Math.min(size, msg.length - i);
            byte[] p = new byte[n];
            System.arraycopy(msg, i, p, 0, n);
            out.add(p);
        }
        return out;
    }

    /**
     * Puts the pieces of the watch's messages together: a message starts with a piece whose first byte is
     * {@link #START} and is complete once its length is reached. Pieces that arrive while no message is open and do not
     * start one are dropped and counted ({@link #dropped}); a piece that runs past the end of a message drops that
     * message (it is damaged), so one bad piece never shifts every message after it. The bytes it drops are not thrown away
     * silently: {@link #take} hands them on, so the session stores them and counts the answer they came in as cut off (a
     * lost piece must never let a start move past records that were not stored, review of dadbb71).
     */
    public static final class Assembler {
        private final ByteArrayOutputStream open = new ByteArrayOutputStream();
        private boolean opened;
        /** Pieces dropped (no message open and no start byte) and messages dropped (longer than their length). */
        public int dropped;
        /** The bytes the last {@link #feed} dropped, null for none. */
        private byte[] lost;

        /** Feeds one piece; returns the message it completes, or null. */
        public byte[] feed(byte[] piece) {
            lost = null;
            if (piece == null || piece.length == 0) return null;
            if (!opened) {
                if ((piece[0] & 0xff) != START) {
                    dropped++;
                    lost = piece;
                    return null;
                }
                opened = true;
                open.reset();
            }
            open.write(piece, 0, piece.length);
            byte[] b = open.toByteArray();
            if (b.length < HEADER) return null;
            int want = HEADER + u16(b, 3);
            if (b.length < want) return null;
            opened = false;
            open.reset();
            if (b.length > want) {
                dropped++;
                lost = b;
                return null;
            }
            return b;
        }

        /**
         * Feeds one piece and returns, in the order they came, what the link hands on: the bytes this piece made the
         * Assembler drop (never {@link Frame#whole whole}, so the session knows them as damaged) and the message it
         * completed. Empty when the piece only adds to an open message.
         */
        public List<byte[]> take(byte[] piece) {
            byte[] m = feed(piece);
            List<byte[]> out = new ArrayList<byte[]>(1);
            if (lost != null) out.add(lost);
            if (m != null) out.add(m);
            lost = null;
            return out;
        }

        /** True while part of a message has arrived and the rest is still missing. */
        public boolean pending() {
            return opened;
        }
    }

    // ------------------------------------------------------------------------------------- numbers

    public static int u16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    public static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    public static int s32(byte[] b, int i) {
        return (int) u32(b, i);
    }

    public static void put16(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 8);
        b[i + 1] = (byte) v;
    }

    public static void put32(byte[] b, int i, long v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    /** Four bytes, big endian. */
    public static byte[] be32(long v) {
        byte[] b = new byte[4];
        put32(b, 0, v);
        return b;
    }

    /** The type as the log writes it, for example {@code 0x0504}. */
    public static String hexType(int t) {
        String s = Integer.toHexString(t & 0xffff);
        while (s.length() < 4) s = "0" + s;
        return "0x" + s;
    }
}
