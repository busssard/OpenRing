package app.openring.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoding and validation of one history batch. Port of open_oura {@code oura-link/src/history.rs}.
 */
public final class HistoryBatch {
    /** A retained batch may skip quiet periods but never by years (180 days in ds). */
    static final long MAX_CURSOR_ADVANCE_DS = 180L * 24 * 60 * 60 * 10;

    public final List<RingEvent> events;
    public final long bytesLeft;
    public long nextCursor;
    public int rejected;
    public int replayed;
    /** Pauses of more than 27 hours inside one extended bundle (see {@link ExtEnvelopeParser}). */
    public int longPauses;

    HistoryBatch(List<RingEvent> events, long bytesLeft) {
        this.events = events;
        this.bytesLeft = bytesLeft;
    }

    public boolean progressed(long previousCursor) {
        return !events.isEmpty() && nextCursor > previousCursor;
    }

    /** Thrown when a batch is unusable (no terminator, or the ring reported an error code). */
    public static final class BatchException extends Exception {
        public final int resultCode;

        public BatchException(String msg, int resultCode) {
            super(msg);
            this.resultCode = resultCode;
        }
    }

    public static HistoryBatch decode(List<Packet> packets) throws BatchException {
        long bytesLeft = -1;
        int resultCode = -1;
        List<RingEvent> events = new ArrayList<RingEvent>();
        ExtEnvelopeParser envelopes = new ExtEnvelopeParser();
        for (Packet p : packets) {
            if (p.tag == 0x11) {
                if (p.payload.length >= 6) bytesLeft = Protocol.Le.u32(p.payload, 2);
            } else if (p.tag == 0x2f && p.extTag() == 0x42) {
                if (p.payload.length >= 10) {
                    bytesLeft = Protocol.Le.u32(p.payload, 4);
                    resultCode = p.payload[9] & 0xff;
                }
            } else if (p.tag == 0x2f && p.extTag() == 0x43) {
                for (Packet e : envelopes.push(p)) {
                    if (e.tag >= Protocol.HISTORY_EVENT_PREFIX) events.add(RingEvent.fromPacket(e));
                }
            } else if (p.tag >= Protocol.HISTORY_EVENT_PREFIX) {
                events.add(RingEvent.fromPacket(p));
            }
        }
        if (bytesLeft < 0) {
            throw new BatchException("event batch ended without a summary packet (" + packets.size()
                    + " packets); the link was probably lost mid batch", -1);
        }
        if (resultCode > 0) {
            throw new BatchException(String.format("extended history request failed with result code 0x%02x", resultCode), resultCode);
        }
        HistoryBatch out = new HistoryBatch(events, bytesLeft);
        out.longPauses = envelopes.longPauses;
        return out;
    }

    /** Drop replayed (older than cursor) and implausible events and compute the next cursor. */
    public static HistoryBatch validate(HistoryBatch batch, long batchStart) {
        long max = batchStart;
        List<RingEvent> kept = new ArrayList<RingEvent>();
        int rejected = 0, replayed = 0;
        for (RingEvent e : batch.events) {
            if (e.ds < batchStart) {
                replayed++;
            } else if (e.ds <= batchStart + MAX_CURSOR_ADVANCE_DS) {
                max = Math.max(max, e.ds);
                kept.add(e);
            } else {
                rejected++;
            }
        }
        HistoryBatch out = new HistoryBatch(kept, batch.bytesLeft);
        out.nextCursor = kept.isEmpty() ? batchStart : max + 1;
        out.rejected = rejected;
        out.replayed = replayed;
        out.longPauses = batch.longPauses;
        return out;
    }
}
