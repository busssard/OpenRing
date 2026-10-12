package app.openring.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps the ring's per boot decisecond counter onto wall clock time.
 *
 * Events are walked in the order the ring delivered them (database id order). A new segment
 * starts where the sync epoch changes or the counter drops by more than six hours (a reboot
 * restarts it near zero), the rule open_health's RingClock uses. Each segment is dated from the
 * anchors inside it: the ring's own time_sync (0x42) and rtc_beacon (0x85) records plus the
 * phone anchors this app writes when a drain reaches the present. Phone anchors are appended
 * after their batch, so they are matched to the newest segment whose counter range covers them
 * instead of opening a segment of their own.
 */
public final class TimeLine {
    private TimeLine() {}

    /** A drop of more than this many ds starts a new boot segment (6 hours, as open_health). */
    public static final long RESET_SLACK_DS = 6L * 3600 * 10;

    public static final class Segment {
        public int no;
        public long firstId, lastId;
        public long minDs = Long.MAX_VALUE, maxDs = Long.MIN_VALUE;
        public long events;
        /** {ds, unix} sorted by ds. */
        public final List<long[]> anchors = new ArrayList<long[]>();
        /** Ids of phone anchor rows that belong to this segment but sit outside its id range. */
        public final List<Long> strayIds = new ArrayList<Long>();
    }

    /**
     * @param id         row ids in ascending order
     * @param epoch      sync epoch per row (changes when the app rebased its cursor)
     * @param ds         ring timestamp per row
     * @param anchorUnix wall clock seconds carried by the row, or -1
     * @param phone      true for the app's own phone anchor rows
     * @param nowUnix    current time; anchors in the future or before 2015 are ignored
     */
    public static List<Segment> build(long[] id, long[] epoch, long[] ds, long[] anchorUnix, boolean[] phone, long nowUnix) {
        List<Segment> segs = new ArrayList<Segment>();
        Segment cur = null;
        long curEpoch = Long.MIN_VALUE;
        List<int[]> phoneRows = new ArrayList<int[]>();
        for (int i = 0; i < id.length; i++) {
            if (phone[i]) {
                phoneRows.add(new int[] {i});
                continue;
            }
            boolean split = cur == null || epoch[i] != curEpoch || ds[i] + RESET_SLACK_DS < cur.maxDs;
            if (split) {
                cur = new Segment();
                cur.no = segs.size() + 1;
                cur.firstId = id[i];
                segs.add(cur);
                curEpoch = epoch[i];
            }
            cur.lastId = id[i];
            cur.events++;
            cur.minDs = Math.min(cur.minDs, ds[i]);
            cur.maxDs = Math.max(cur.maxDs, ds[i]);
            if (valid(anchorUnix[i], nowUnix)) cur.anchors.add(new long[] {ds[i], anchorUnix[i]});
        }
        // phone anchors: newest segment (at or before the row) whose range covers them
        for (int[] pr : phoneRows) {
            int i = pr[0];
            if (!valid(anchorUnix[i], nowUnix)) continue;
            Segment best = null;
            for (int k = segs.size() - 1; k >= 0; k--) {
                Segment s = segs.get(k);
                if (s.firstId > id[i]) continue;
                if (epoch[i] == epochOf(s, id, epoch) && ds[i] >= s.minDs - RESET_SLACK_DS && ds[i] <= s.maxDs + RESET_SLACK_DS) {
                    best = s;
                    break;
                }
            }
            if (best == null) continue;
            best.anchors.add(new long[] {ds[i], anchorUnix[i]});
            best.strayIds.add(id[i]);
        }
        for (Segment s : segs) {
            java.util.Collections.sort(s.anchors, new java.util.Comparator<long[]>() {
                @Override
                public int compare(long[] a, long[] b) {
                    return Long.compare(a[0], b[0]);
                }
            });
        }
        return segs;
    }

    private static long epochOf(Segment s, long[] id, long[] epoch) {
        int lo = 0, hi = id.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (id[mid] < s.firstId) lo = mid + 1;
            else hi = mid;
        }
        return epoch[lo];
    }

    static boolean valid(long unix, long now) {
        return unix > 1_420_000_000L && unix <= now + 6 * 3600;
    }

    /**
     * Wall clock time for a ds inside a segment: the nearest anchor, advanced at 10 ds per
     * second. NaN when the segment has no anchor.
     */
    public static double unixAt(Segment s, long ds) {
        if (s.anchors.isEmpty()) return Double.NaN;
        long[] best = s.anchors.get(0);
        for (long[] a : s.anchors) if (Math.abs(a[0] - ds) < Math.abs(best[0] - ds)) best = a;
        return best[1] + (ds - best[0]) / 10.0;
    }

    /** {lo, hi, anchorDs, anchorUnix} ds regions (hi exclusive) where each anchor is nearest. */
    public static List<long[]> regions(Segment s) {
        List<long[]> out = new ArrayList<long[]>();
        List<long[]> a = s.anchors;
        for (int i = 0; i < a.size(); i++) {
            long lo = i == 0 ? Long.MIN_VALUE / 4 : (a.get(i - 1)[0] + a.get(i)[0]) / 2;
            long hi = i == a.size() - 1 ? Long.MAX_VALUE / 4 : (a.get(i)[0] + a.get(i + 1)[0]) / 2;
            if (hi > lo) out.add(new long[] {lo, hi, a.get(i)[0], a.get(i)[1]});
        }
        return out;
    }

    /** Body of the app's phone anchor: u32 LE unix seconds then "phone" (open_health format). */
    public static byte[] phoneAnchorBody(long unix) {
        byte[] b = new byte[9];
        Protocol.Le.putU32(b, 0, unix);
        b[4] = 'p';
        b[5] = 'h';
        b[6] = 'o';
        b[7] = 'n';
        b[8] = 'e';
        return b;
    }
}
