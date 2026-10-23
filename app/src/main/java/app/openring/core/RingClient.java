package app.openring.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * High level, transport independent ring client. Port of open_oura {@code oura-link/src/client.rs}
 * (request/terminal handling, auth, the history drain loop with its Ring 3/4/5 workarounds).
 * Every call blocks, so run it on a worker thread.
 */
public final class RingClient {

    /** The byte pipe to the ring: writes to 98ed0002, notifications from 98ed0003. */
    public interface Link {
        /** Write one request and wait for the GATT write to complete. */
        void write(byte[] data) throws IOException;

        /** Next notification frame, or null after {@code timeoutMs}. */
        byte[] poll(long timeoutMs) throws InterruptedException, IOException;

        /** Drop any frames that arrived before the next request. */
        void clearInbox();
    }

    /** Decides when a response is complete. */
    public interface Terminal {
        boolean done(Packet p);
    }

    /** Receives each drained batch; return false to abort without advancing the cursor. */
    public interface BatchSink {
        boolean onBatch(List<RingEvent> events, long nextCursor, long bytesLeft, int totalSoFar);
    }

    public interface Log {
        void log(String msg);
    }

    public static final long DEFAULT_QUIET_MS = 1500;
    static final long DRAIN_QUIET_MS = 6000;
    static final int EXT_BATCH_MAX_EVENTS = 4096;
    static final int MAX_TAIL_PASSES = 3;
    static final int TAIL_BATCH_EVENTS = 32;

    private final Link link;
    private long quietMs = DEFAULT_QUIET_MS;
    private Log log = new Log() {
        @Override
        public void log(String msg) {}
    };
    private volatile boolean cancelled;

    public RingClient(Link link) {
        this.link = link;
    }

    public RingClient quiet(long ms) {
        this.quietMs = ms;
        return this;
    }

    public void setLog(Log l) {
        if (l != null) log = l;
    }

    public void cancel() {
        cancelled = true;
    }

    public static class RingException extends IOException {
        public RingException(String m) {
            super(m);
        }
    }

    public static final class AuthException extends RingException {
        public final int state;

        public AuthException(int state, String m) {
            super(m);
            this.state = state;
        }
    }

    // ---- request plumbing ---------------------------------------------------------------

    public List<Packet> request(byte[] bytes, Terminal terminal, long quiet) throws IOException, InterruptedException {
        return request(bytes, terminal, quiet, true);
    }

    /** {@code clear=false} keeps frames already waiting (live streams must not lose them). */
    public List<Packet> request(byte[] bytes, Terminal terminal, long quiet, boolean clear) throws IOException, InterruptedException {
        if (cancelled) throw new RingException("cancelled");
        if (clear) link.clearInbox();
        link.write(bytes);
        List<Packet> out = new ArrayList<Packet>();
        while (true) {
            byte[] frame = link.poll(quiet);
            if (frame == null) break;
            boolean done = false;
            for (Packet p : Packet.parseMany(frame)) {
                out.add(p);
                if (terminal != null && terminal.done(p)) done = true;
            }
            if (done) break;
            if (cancelled) throw new RingException("cancelled");
        }
        return out;
    }

    public List<Packet> requestTag(byte[] bytes, final int tag) throws IOException, InterruptedException {
        return request(bytes, new Terminal() {
            @Override
            public boolean done(Packet p) {
                return p.tag == tag;
            }
        }, quietMs);
    }

    public List<Packet> requestExt(byte[] bytes, final int ext) throws IOException, InterruptedException {
        return request(bytes, new Terminal() {
            @Override
            public boolean done(Packet p) {
                return p.extTag() == ext || Protocol.isAuthRequired(p);
            }
        }, quietMs);
    }

    static Packet find(List<Packet> ps, int tag) {
        for (Packet p : ps) if (p.tag == tag) return p;
        return null;
    }

    static Packet findExt(List<Packet> ps, int ext) {
        for (Packet p : ps) if (p.extTag() == ext) return p;
        return null;
    }

    // ---- device info --------------------------------------------------------------------

    public static final class DeviceInfo {
        public String api, firmware, bootloader, btStack, mac;
    }

    public DeviceInfo firmware() throws IOException, InterruptedException {
        Packet p = find(requestTag(Protocol.firmware(), 0x09), 0x09);
        if (p == null || p.payload.length < 18) throw new RingException("no firmware response");
        DeviceInfo d = new DeviceInfo();
        d.api = v3(p.payload, 0);
        d.firmware = v3(p.payload, 3);
        d.bootloader = v3(p.payload, 6);
        d.btStack = v3(p.payload, 9);
        StringBuilder mac = new StringBuilder();
        for (int i = 17; i >= 12; i--) {
            if (mac.length() > 0) mac.append(':');
            mac.append(String.format("%02X", p.payload[i] & 0xff));
        }
        d.mac = mac.toString();
        return d;
    }

    private static String v3(byte[] b, int o) {
        return (b[o] & 0xff) + "." + (b[o + 1] & 0xff) + "." + (b[o + 2] & 0xff);
    }

    /** {percent, chargingProgress, chargingRecommended}. Requires auth. */
    public int[] battery() throws IOException, InterruptedException {
        Packet p = find(requestTag(Protocol.battery(), 0x0d), 0x0d);
        if (p == null || p.payload.length < 3) throw new RingException("no battery response");
        return new int[] {p.u8(0), p.u8(1), p.u8(2)};
    }

    public String productString(byte[] req) throws IOException, InterruptedException {
        Packet p = find(requestTag(req, 0x19), 0x19);
        if (p == null || p.payload.length < 2 || p.payload[0] != 0) return null;
        String s = EventCodec.ascii(p.payload, 1);
        return s.isEmpty() ? null : s;
    }

    public String serial() throws IOException, InterruptedException {
        String s = productString(Protocol.SERIAL);
        if (s == null) throw new RingException("no serial response");
        return s;
    }

    public String hardwareId() throws IOException, InterruptedException {
        return productString(Protocol.HARDWARE);
    }

    // ---- auth ---------------------------------------------------------------------------

    public int authenticate(byte[] key) throws IOException, InterruptedException {
        List<Packet> ps = requestExt(Protocol.authNonce(), 0x2c);
        Packet n = findExt(ps, 0x2c);
        if (n == null || n.payload.length < 2) {
            throw new RingException("no nonce response from the ring " + ps);
        }
        byte[] nonce = new byte[n.payload.length - 1];
        System.arraycopy(n.payload, 1, nonce, 0, nonce.length);
        byte[] enc = Auth.encryptNonce(key, nonce);
        ps = requestExt(Protocol.authenticate(enc), 0x2e);
        Packet r = findExt(ps, 0x2e);
        if (r == null || r.payload.length < 2) throw new RingException("no authenticate response " + ps);
        int state = r.payload[1] & 0xff;
        if (state != Auth.OK) throw new AuthException(state, "authentication failed: " + Auth.describe(state));
        return state;
    }

    /** Install a key (factory reset ring only). Returns the ring's status byte (0 = ok). */
    public int setAuthKey(byte[] key) throws IOException, InterruptedException {
        Packet p = find(requestTag(Protocol.setAuthKey(key), 0x25), 0x25);
        if (p == null || p.payload.length < 1) throw new RingException("no set key response, is the ring factory reset?");
        return p.payload[0] & 0xff;
    }

    // ---- session ------------------------------------------------------------------------

    /** Sets the ring clock. Returns the ring's current time counter from the reply, or -1. */
    public long syncTime(long unixSecs, int tzHalfHours) throws IOException, InterruptedException {
        Packet p = find(requestTag(Protocol.syncTime(unixSecs, tzHalfHours), 0x13), 0x13);
        if (p != null && p.payload.length >= 4) return Protocol.Le.u32(p.payload, 0);
        return -1;
    }

    public int checkSleepAnalysis(boolean force) throws IOException, InterruptedException {
        Packet p = find(requestTag(Protocol.checkSleepAnalysis(force), 0x29), 0x29);
        return p == null ? -1 : p.u8(0);
    }

    /** {mode, status, state, subscription} or null. */
    public int[] featureStatus(int feature) throws IOException, InterruptedException {
        Packet p = findExt(requestExt(Protocol.featureStatus(feature), 0x21), 0x21);
        if (p == null || p.payload.length < 6) return null;
        return new int[] {p.u8(2), p.u8(3), p.u8(4), p.u8(5)};
    }

    public boolean setFeatureMode(int feature, int mode) throws IOException, InterruptedException {
        Packet p = findExt(requestExt(Protocol.setFeatureMode(feature, mode), 0x23), 0x23);
        return p != null && p.u8(2) == 0;
    }

    /** Latest cached measurement: {bpm or -1, spo2 or -1}. */
    public int[] featureLatest(int feature) throws IOException, InterruptedException {
        Packet p = findExt(requestExt(Protocol.featureLatest(feature), 0x25), 0x25);
        int[] out = {-1, -1};
        if (p == null || p.payload.length <= 7) return out;
        byte[] d = new byte[p.payload.length - 7];
        System.arraycopy(p.payload, 7, d, 0, d.length);
        if (feature == Protocol.FEATURE_DAYTIME_HR && d.length >= 2) {
            int ibi = Protocol.Le.u16(d, 0);
            if (EventCodec.plausibleIbi(ibi)) out[0] = 60000 / ibi;
        } else if (feature == Protocol.FEATURE_SPO2 && d.length >= 5) {
            if ((d[3] & 0xff) > 0) out[1] = d[3] & 0xff;
            if ((d[4] & 0xff) > 0) out[0] = d[4] & 0xff;
        } else if (feature == Protocol.FEATURE_EXERCISE_HR && d.length >= 5) {
            if ((d[4] & 0xff) > 0) out[0] = d[4] & 0xff;
        }
        return out;
    }

    /** Ring 5 app style stream registration (skipped on other generations, as open_oura does). */
    public void setupAppStream(String hardwareId) throws IOException, InterruptedException {
        if (hardwareId == null || !hardwareId.endsWith("_05")) return;
        requestTag(Protocol.streamSubscribe(0x02), 0x17);
        int[][] cats = {{0x14, 0x1000}, {0x18, 0x1000}, {0x28, 0x0900}, {0x34, 0x0400}, {0x04, 0x1000}, {0x08, 0x1000}};
        for (int[] c : cats) requestTag(Protocol.eventSubscribe(c[0], c[1]), 0x19);
        requestExt(Protocol.featureStatus(0x02), 0x21);
        requestExt(Protocol.featureStatus(0x04), 0x21);
        requestExt(new byte[] {0x2f, 0x02, 0x03, 0x01}, 0x04);
        int[] params = {0x0b, 0x0d, 0x03, 0x0b, 0x10};
        for (int id : params) requestExt(Protocol.featureStatus(id), 0x21);
    }

    /** A live feature indication (ext 0x28): feature, status, state, and a beat when valid. */
    public static final class LiveIndication {
        public int feature, status, state, bpm = -1, ibi = -1, validity = -1;
    }

    /**
     * Parse a live indication from one packet: {@code 2f len 28 <feature> <status> <state>
     * <timeSince u16> <IBI u16>}. The IBI word packs 12 bits of interval and a 4 bit validity
     * nibble (1 = valid). Works on any packet of a concatenated notification.
     */
    public static LiveIndication parseLive(Packet p) {
        if (p.tag != 0x2f || p.payload.length < 4 || (p.payload[0] & 0xff) != 0x28) return null;
        LiveIndication li = new LiveIndication();
        li.feature = p.u8(1);
        li.status = p.u8(2);
        li.state = p.u8(3);
        if (p.payload.length >= 8) {
            int lo = p.u8(6), hi = p.u8(7);
            int ibi = ((hi & 0x0f) << 8) | lo;
            li.validity = (hi >> 4) & 0x0f;
            li.ibi = ibi;
            if (li.validity == 1 && EventCodec.plausibleIbi(ibi)) li.bpm = 60000 / ibi;
        }
        return li;
    }

    /** Ring feature status names (ringverse): what the sensor is doing right now. */
    public static String statusText(int status) {
        switch (status) {
            case 0: return "measurement off";
            case 1: return "measuring";
            case 2: return "searching for your pulse";
            case 3: return "no pulse signal, wear the ring snugly";
            case 4: return "fingers too cold";
            case 5: return "too much motion, keep your hand still";
            case 6: return "identifying";
            default: return "status " + status;
        }
    }

    /** Parse a live daytime HR notification (2f .. 28 02 ...): returns {bpm, ibiMs} or null. */
    public static int[] parseLiveHr(byte[] f) {
        if (f == null || f.length < 10 || (f[0] & 0xff) != 0x2f || (f[2] & 0xff) != 0x28) return null;
        if ((f[3] & 0xff) != Protocol.FEATURE_DAYTIME_HR) return null;
        int lo = f[8] & 0xff, hi = f[9] & 0xff;
        int ibi = ((hi & 0x0f) << 8) | lo;
        int validity = (hi >> 4) & 0x0f;
        if (validity != 1 || !EventCodec.plausibleIbi(ibi)) return null;
        return new int[] {60000 / ibi, ibi};
    }

    // ---- history drain ------------------------------------------------------------------

    public static final class DrainResult {
        public int eventsSynced;
        public long nextCursor;
    }

    private final Terminal batchTerminal = new Terminal() {
        @Override
        public boolean done(Packet p) {
            if (p.tag == 0x11) return true;
            if (p.tag == 0x2f) {
                int e = p.extTag();
                return e == 0x42 || e == 0x00;
            }
            return false;
        }
    };

    /**
     * Drain history events from {@code cursor} (deciseconds), checkpointing after every batch via
     * {@code sink}. Prefers the extended (Ring 4/5) request and falls back to legacy GetEvent.
     */
    public DrainResult drain(long cursor, BatchSink sink) throws IOException, InterruptedException, HistoryBatch.BatchException {
        long start = cursor;
        int total = 0;
        int tailPasses = 0;
        boolean useExtended = true;
        long batchQuiet = Math.max(quietMs, Math.min(quietMs * 4, DRAIN_QUIET_MS));
        for (int guard = 0; guard < 100000; guard++) {
            if (cancelled) throw new RingException("cancelled");
            List<Packet> packets = new ArrayList<Packet>(requestTag(Protocol.dataFlush(), 0x29));
            if (useExtended) {
                List<Packet> ext = request(Protocol.extGetEvent(start * 100, EXT_BATCH_MAX_EVENTS, 0), batchTerminal, batchQuiet);
                boolean unsupported = false;
                for (Packet p : ext) if (p.tag == 0x2f && p.extTag() == 0x00) unsupported = true;
                if (unsupported) {
                    log.log("extended event sync unsupported, using legacy GetEvent");
                    useExtended = false;
                    packets.addAll(request(Protocol.getEvent(start, 255, -1), batchTerminal, batchQuiet));
                } else {
                    packets.addAll(ext);
                }
            } else {
                packets.addAll(request(Protocol.getEvent(start, 255, -1), batchTerminal, batchQuiet));
            }
            HistoryBatch batch = HistoryBatch.validate(HistoryBatch.decode(packets), start);
            total += batch.events.size();
            if (batch.rejected > 0) log.log("discarded " + batch.rejected + " events with impossible timestamps");
            if (batch.longPauses > 0) log.log(longPauseText(batch.longPauses, batch.longestPauseMs));
            long bytesLeft = batch.bytesLeft;
            boolean progressed = batch.progressed(start);
            boolean tail = progressed && bytesLeft == 0 && batch.events.size() < TAIL_BATCH_EVENTS;
            if (progressed) start = batch.nextCursor;
            if (!sink.onBatch(batch.events, start, bytesLeft, total)) {
                throw new RingException("storage failed; batch not acknowledged");
            }
            if (!progressed) {
                if (bytesLeft == 0) break;
                throw new RingException("ring reports " + bytesLeft + " bytes left but the batch was empty (cursor " + start + ")");
            }
            if (tail) {
                tailPasses++;
                if (tailPasses >= MAX_TAIL_PASSES) break;
            } else {
                tailPasses = 0;
            }
        }
        DrainResult r = new DrainResult();
        r.eventsSynced = total;
        r.nextCursor = start;
        return r;
    }

    /** Log line for pauses of 27.8 h or more inside extended bundles (issue #31). */
    public static String longPauseText(int n, long longestMs) {
        String hours = String.format(java.util.Locale.ROOT, "%.1f", longestMs / 3_600_000.0);
        return "history: " + n + (n == 1 ? " pause" : " pauses")
                + " of more than 27 hours between stored events, kept as real time (longest " + hours + " h)";
    }

    /** Probe whether the event right before {@code cursor} still exists (detects a reboot). */
    public boolean cursorMarkerPresent(long cursor) throws IOException, InterruptedException {
        if (cursor == 0) return true;
        try {
            DrainResult r = drain(cursor - 1, new BatchSink() {
                @Override
                public boolean onBatch(List<RingEvent> events, long next, long left, int tot) {
                    return true;
                }
            });
            return r.eventsSynced > 0 && r.nextCursor >= cursor;
        } catch (HistoryBatch.BatchException e) {
            if (e.resultCode == 0xff) return false; // the ring rejected the cursor itself
            throw new RingException(e.getMessage());
        }
    }

    /** Factory reset. The ring drops the link without answering; that is the expected outcome. */
    public void factoryReset() throws InterruptedException {
        try {
            request(Protocol.factoryReset(), null, 3000);
        } catch (IOException ignored) {
            // expected: the link drops
        }
    }
}
