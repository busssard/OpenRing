package app.openring.core;

import java.util.UUID;

/**
 * GATT UUIDs, constants and request builders for the Oura ring protocol (Ring 3, 4 and 5 share
 * these). Port of open_oura {@code protocol.rs}, cross checked against the ringverse Ring 4 notes.
 */
public final class Protocol {
    private Protocol() {}

    public static final UUID SERVICE = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b");
    public static final UUID WRITE = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b");
    public static final UUID NOTIFY = UUID.fromString("98ed0003-a541-11e4-b6a0-0002a5d5c51b");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** Bluetooth SIG company id used by Oura in advertising manufacturer data. */
    public static final int COMPANY_ID = 0x02b2;

    /** Tags at or above this value are history events rather than command replies. */
    public static final int HISTORY_EVENT_PREFIX = 0x41;

    // Feature ids (extended 0x2f feature ops)
    public static final int FEATURE_BACKGROUND_DFU = 0x00;
    public static final int FEATURE_RESEARCH_DATA = 0x01;
    public static final int FEATURE_DAYTIME_HR = 0x02;
    public static final int FEATURE_EXERCISE_HR = 0x03;
    public static final int FEATURE_SPO2 = 0x04;
    public static final int FEATURE_RESTING_HR = 0x08;
    public static final int FEATURE_REAL_STEPS = 0x0b;

    // Feature modes
    public static final int MODE_OFF = 0x00;
    public static final int MODE_AUTOMATIC = 0x01;
    public static final int MODE_REQUESTED = 0x02;
    public static final int MODE_CONNECTED_LIVE = 0x03;

    public static byte[] firmware() {
        return new byte[] {0x08, 0x03, 0x00, 0x00, 0x00};
    }

    public static byte[] battery() {
        return new byte[] {0x0c, 0x00};
    }

    public static final byte[] SERIAL = {0x18, 0x03, 0x08, 0x00, 0x10};
    public static final byte[] HARDWARE = {0x18, 0x03, 0x18, 0x00, 0x10};
    public static final byte[] PRODUCT_CODE = {0x18, 0x03, 0x28, 0x00, 0x09};

    public static byte[] authNonce() {
        return new byte[] {0x2f, 0x01, 0x2b};
    }

    public static byte[] authenticate(byte[] encrypted16) {
        byte[] p = new byte[17];
        p[0] = 0x2d;
        System.arraycopy(encrypted16, 0, p, 1, 16);
        return new Packet(0x2f, p).encode();
    }

    /** Install a 16 byte auth key on a factory reset ring. */
    public static byte[] setAuthKey(byte[] key16) {
        return new Packet(0x24, key16.clone()).encode();
    }

    /** {@code 12 09 <u64 unix seconds> <timezone in half hours>} (ringverse, Ring 4). */
    public static byte[] syncTime(long unixSecs, int tzHalfHours) {
        byte[] p = new byte[9];
        Le.putU64(p, 0, unixSecs);
        p[8] = (byte) tzHalfHours;
        return new Packet(0x12, p).encode();
    }

    /** Forced time set {@code 12 0a <u64> <tz> 01}: the ring may skip the plain one while measuring (OpenStrap). */
    public static byte[] syncTimeForced(long unixSecs, int tzHalfHours) {
        byte[] p = new byte[10];
        Le.putU64(p, 0, unixSecs);
        p[8] = (byte) tzHalfHours;
        p[9] = 0x01;
        return new Packet(0x12, p).encode();
    }

    public static byte[] streamSubscribe(int mode) {
        return new Packet(0x16, new byte[] {(byte) mode}).encode();
    }

    public static byte[] eventSubscribe(int category, int flags) {
        return new Packet(0x18, new byte[] {(byte) category, (byte) flags, (byte) (flags >> 8)}).encode();
    }

    public static byte[] setNotification(int flags) {
        return new Packet(0x1c, new byte[] {(byte) flags}).encode();
    }

    public static byte[] capabilities(int page) {
        return new byte[] {0x2f, 0x02, 0x01, (byte) page};
    }

    /** Legacy GetEvent: {@code 10 09 <u32 start ds> <u8 max> <i32 flags>}. */
    public static byte[] getEvent(long startDs, int maxEvents, int flags) {
        byte[] p = new byte[9];
        Le.putU32(p, 0, startDs);
        p[4] = (byte) maxEvents;
        Le.putU32(p, 5, flags & 0xffffffffL);
        return new Packet(0x10, p).encode();
    }

    /** Extended GetEvent: {@code 2f 0c 41 <buffer> <u64 start ms> <u16 max>}. */
    public static byte[] extGetEvent(long startMs, int maxEvents, int bufferId) {
        byte[] p = new byte[12];
        p[0] = 0x41;
        p[1] = (byte) bufferId;
        Le.putU64(p, 2, startMs);
        p[10] = (byte) maxEvents;
        p[11] = (byte) (maxEvents >> 8);
        return new Packet(0x2f, p).encode();
    }

    /** Releases flash buffered data into the notification stream ({@code 28 01 00}). */
    public static byte[] dataFlush() {
        return new byte[] {0x28, 0x01, 0x00};
    }

    public static byte[] checkSleepAnalysis(boolean force) {
        return new byte[] {0x28, 0x01, (byte) (force ? 1 : 0)};
    }

    public static byte[] featureStatus(int feature) {
        return new byte[] {0x2f, 0x02, 0x20, (byte) feature};
    }

    public static byte[] featureLatest(int feature) {
        return new byte[] {0x2f, 0x02, 0x24, (byte) feature};
    }

    public static byte[] setFeatureMode(int feature, int mode) {
        return new byte[] {0x2f, 0x03, 0x22, (byte) feature, (byte) mode};
    }

    public static byte[] setFeatureSubscription(int capability, int mode) {
        return new byte[] {0x2f, 0x03, 0x26, (byte) capability, (byte) mode};
    }

    /** Real time measurement {@code 06 07 <u32 type mask> <u16 minutes> <u8 delay>} (open_oura). */
    public static byte[] realtime(long mask, int minutes) {
        byte[] p = new byte[7];
        Le.putU32(p, 0, mask);
        p[4] = (byte) minutes;
        p[5] = (byte) (minutes >> 8);
        return new Packet(0x06, p).encode();
    }

    /** Stop all real time measurements. */
    public static byte[] realtimeOff() {
        return new Packet(0x06, new byte[4]).encode();
    }

    /** Real time type bit for the live accelerometer stream (frames tagged 0x33). */
    public static final long REALTIME_ACM = 0x20;

    /** Real time type bit for an on demand PPG / heart rate sample. */
    public static final long REALTIME_ON_DEMAND = 0x200;

    /** Factory reset: erases the key, bonds and on ring history. Kept out of normal flows. */
    public static byte[] factoryReset() {
        return new byte[] {0x1a, 0x00};
    }

    /** True when the ring answered "authenticate first" ({@code 2f 02 2f 01}). */
    public static boolean isAuthRequired(Packet p) {
        return p.tag == 0x2f && p.payload.length >= 2 && (p.payload[0] & 0xff) == 0x2f && p.payload[1] == 0x01;
    }

    /** Little endian helpers. */
    public static final class Le {
        private Le() {}

        public static void putU32(byte[] b, int off, long v) {
            for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >>> (8 * i));
        }

        public static void putU64(byte[] b, int off, long v) {
            for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
        }

        public static int u16(byte[] b, int off) {
            return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
        }

        public static int i16(byte[] b, int off) {
            return (short) u16(b, off);
        }

        public static long u32(byte[] b, int off) {
            return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8) | ((b[off + 2] & 0xffL) << 16)
                    | ((b[off + 3] & 0xffL) << 24);
        }

        public static long u64(byte[] b, int off) {
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
            return v;
        }
    }
}
