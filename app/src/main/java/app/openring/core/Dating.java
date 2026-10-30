package app.openring.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Works out every event's boot segment and date by the rules of {@link TimeLine}, in two ways that give the same
 * result bit for bit (issue #220):
 * <ul>
 * <li>{@link #all}: from scratch, every row (the in memory replay of the old full rewrite, issue #21);</li>
 * <li>{@link #step}: after a sync, from the {@link State} the run before stored, reading only the new rows, the old
 * rows of the newest boot from the midpoint between its last two time records on, and that boot's time_sync rows.
 * Whenever a case is not plainly one where only that part can change, it says so ({@link Step#full}) and the caller
 * does the full run.</li>
 * </ul>
 * Why the step is exact: new rows only extend the newest segment or open new ones (segments split in id order); a
 * time record of the newest segment with a later counter than all its old ones only moves the borders after its last
 * old record ({@link TimeLine#regions}: a border is the midpoint of two neighbouring records), so rows before the
 * midpoint of the last two old records keep their region; the limits that depend on now (records more than 6 h
 * ahead are ignored, dates more than 6 h ahead are cleared) are kept as bounds, and a now that crosses one means the
 * full run. Pure Java, no Android.
 */
public final class Dating {
    private Dating() {}

    /** Dates before this are cleared (2015). */
    static final double EARLIEST = 1420000000;

    /** The rows a run reads, by ascending id. {@code anchor} is the wall clock of a time record row, else -1. */
    public static final class Rows {
        public long[] id, epoch, ds, anchor;
        public boolean[] phone;
        public int n;

        public Rows(int capacity) {
            id = new long[capacity];
            epoch = new long[capacity];
            ds = new long[capacity];
            anchor = new long[capacity];
            phone = new boolean[capacity];
        }

        public void add(long id, long epoch, long ds, long anchor, boolean phone) {
            if (n == this.id.length) grow();
            this.id[n] = id;
            this.epoch[n] = epoch;
            this.ds[n] = ds;
            this.anchor[n] = anchor;
            this.phone[n] = phone;
            n++;
        }

        private void grow() {
            int c = Math.max(16, id.length * 2);
            id = Arrays.copyOf(id, c);
            epoch = Arrays.copyOf(epoch, c);
            ds = Arrays.copyOf(ds, c);
            anchor = Arrays.copyOf(anchor, c);
            phone = Arrays.copyOf(phone, c);
        }

        void trim() {
            if (id.length == n) return;
            id = Arrays.copyOf(id, n);
            epoch = Arrays.copyOf(epoch, n);
            ds = Arrays.copyOf(ds, n);
            anchor = Arrays.copyOf(anchor, n);
            phone = Arrays.copyOf(phone, n);
        }
    }

    /** One boot segment as the state keeps it (its non phone rows: first and last id, epoch, counter range). */
    public static final class Summary {
        public int no;
        public long firstId, lastId, epoch, minDs = Long.MAX_VALUE, maxDs = Long.MIN_VALUE, events;
        public boolean anchored;

        Summary copy() {
            Summary s = new Summary();
            s.no = no;
            s.firstId = firstId;
            s.lastId = lastId;
            s.epoch = epoch;
            s.minDs = minDs;
            s.maxDs = maxDs;
            s.events = events;
            s.anchored = anchored;
            return s;
        }
    }

    /**
     * What the last run left (kv {@code time_state}), stored in the same transaction as the dates. Bounds marked
     * "bound" may lie on the safe side (a crossed bound only means a full run), the counts and the range are exact.
     */
    public static final class State {
        public static final int VERSION = 1;
        /** Newest id dated, the run's now, rows, dated rows, time record rows (tag 0x42 or 0x85). */
        public long lastId, now, total, dated, anchorRows;
        /** Earliest and latest date stored (NaN when none). */
        public double dmin = Double.NaN, dmax = Double.NaN;
        /** Bound: the earliest time record after 2015 ignored as in the future. */
        public long futMin = Long.MAX_VALUE;
        /** Bound: the latest time record in use. */
        public long validMax = Long.MIN_VALUE;
        /** Bound: the earliest date cleared as more than 6 h ahead. */
        public double clampMin = Double.POSITIVE_INFINITY;
        public final List<Summary> segs = new ArrayList<Summary>();
        /** The last (at most two) time records of the newest segment, {ds, unix, phone ? 1 : 0}, in the order dating sorts them. */
        public final List<long[]> tail = new ArrayList<long[]>();

        public String encode() {
            StringBuilder b = new StringBuilder();
            b.append(VERSION).append(' ').append(TimeLine.RULES).append(' ').append(lastId).append(' ').append(now).append(' ').append(total).append(' ').append(dated)
                    .append(' ').append(anchorRows).append(' ').append(Double.toString(dmin)).append(' ').append(Double.toString(dmax))
                    .append(' ').append(futMin).append(' ').append(validMax).append(' ').append(Double.toString(clampMin));
            b.append(' ').append(tail.size());
            for (long[] a : tail) b.append(' ').append(a[0]).append(' ').append(a[1]).append(' ').append(a[2]);
            b.append(' ').append(segs.size());
            for (Summary s : segs) {
                b.append(' ').append(s.no).append(' ').append(s.firstId).append(' ').append(s.lastId).append(' ').append(s.epoch)
                        .append(' ').append(s.minDs).append(' ').append(s.maxDs).append(' ').append(s.events).append(' ').append(s.anchored ? 1 : 0);
            }
            return b.toString();
        }

        /** The state in {@code text}; null when there is none, it is damaged, from another version or other dating rules. */
        public static State decode(String text) {
            if (text == null) return null;
            try {
                String[] t = text.trim().split(" ");
                int i = 0;
                if (Integer.parseInt(t[i++]) != VERSION) return null;
                // dates stored under other dating rules are all worked out again
                if (Integer.parseInt(t[i++]) != TimeLine.RULES) return null;
                State s = new State();
                s.lastId = Long.parseLong(t[i++]);
                s.now = Long.parseLong(t[i++]);
                s.total = Long.parseLong(t[i++]);
                s.dated = Long.parseLong(t[i++]);
                s.anchorRows = Long.parseLong(t[i++]);
                s.dmin = Double.parseDouble(t[i++]);
                s.dmax = Double.parseDouble(t[i++]);
                s.futMin = Long.parseLong(t[i++]);
                s.validMax = Long.parseLong(t[i++]);
                s.clampMin = Double.parseDouble(t[i++]);
                int nt = Integer.parseInt(t[i++]);
                if (nt < 0 || nt > 2) return null;
                for (int k = 0; k < nt; k++) s.tail.add(new long[] {Long.parseLong(t[i++]), Long.parseLong(t[i++]), Long.parseLong(t[i++])});
                int ns = Integer.parseInt(t[i++]);
                if (ns < 0) return null;
                for (int k = 0; k < ns; k++) {
                    Summary g = new Summary();
                    g.no = Integer.parseInt(t[i++]);
                    g.firstId = Long.parseLong(t[i++]);
                    g.lastId = Long.parseLong(t[i++]);
                    g.epoch = Long.parseLong(t[i++]);
                    g.minDs = Long.parseLong(t[i++]);
                    g.maxDs = Long.parseLong(t[i++]);
                    g.events = Long.parseLong(t[i++]);
                    g.anchored = Integer.parseInt(t[i++]) == 1;
                    if (g.no != k + 1 || g.firstId > g.lastId || (k > 0 && g.firstId <= s.segs.get(k - 1).lastId)) return null;
                    s.segs.add(g);
                }
                if (i != t.length || s.dated < 0 || s.dated > s.total || (s.dated > 0 && (Double.isNaN(s.dmin) || Double.isNaN(s.dmax)))) return null;
                if (!s.segs.isEmpty() && s.tail.isEmpty() == s.segs.get(s.segs.size() - 1).anchored) return null;
                return s;
            } catch (RuntimeException e) {
                return null;
            }
        }

        /**
         * Null when the dates stored for this state are still the ones {@code nowUnix} gives (no time record and no
         * date crosses the 6 hour limit); else why not.
         */
        public String changesAt(long nowUnix) {
            long ceil = nowUnix + 6 * 3600;
            if (ceil >= futMin) return "a time record that was ahead is due now";
            if (ceil < validMax) return "a time record in use is ahead of the phone clock now";
            if (ceil >= clampMin) return "a date cleared as ahead is due now";
            if (!Double.isNaN(dmax) && ceil < dmax) return "a stored date is ahead of the phone clock now";
            return null;
        }
    }

    /** The result of {@link #all}. */
    public static final class Dated {
        public List<TimeLine.Segment> segs;
        /** Per row: segment number or -1, wall clock time or NaN (after the 2015 and 6 hour limits). */
        public int[] seg;
        public double[] unix;
        /** The state of this result; the caller sets {@code anchorRows}. */
        public State state;
    }

    /**
     * Segment and date of every row from scratch, as the full rewrite did (issue #21): everything undated, then per
     * segment its rows and stray phone anchors get the segment, then the dates of its regions, then the limits.
     * Clears {@code r}'s epoch, anchor and phone arrays once they are used, so the caller's memory can go.
     */
    public static Dated all(Rows r, long nowUnix) {
        r.trim();
        int n = r.n;
        long[] id = r.id, ds = r.ds;
        List<TimeLine.Segment> segs = TimeLine.build(id, r.epoch, ds, r.anchor, r.phone, nowUnix);
        State st = new State();
        long ceil = nowUnix + 6 * 3600;
        for (int k = 0; k < n; k++) {
            long a = r.anchor[k];
            if (a <= 1_420_000_000L) continue;
            if (a <= ceil) st.validMax = Math.max(st.validMax, a);
            else st.futMin = Math.min(st.futMin, a);
        }
        for (TimeLine.Segment sg : segs) {
            Summary s = new Summary();
            s.no = sg.no;
            s.firstId = sg.firstId;
            s.lastId = sg.lastId;
            s.epoch = r.epoch[Arrays.binarySearch(id, 0, n, sg.firstId)];
            s.minDs = sg.minDs;
            s.maxDs = sg.maxDs;
            s.events = sg.events;
            s.anchored = !sg.anchors.isEmpty();
            st.segs.add(s);
        }
        if (!segs.isEmpty()) {
            // the newest segment's records with their kind, sorted as build sorts them: its ring records, its phone anchors
            TimeLine.Segment sg = segs.get(segs.size() - 1);
            List<long[]> a = new ArrayList<long[]>();
            int lo = Arrays.binarySearch(id, 0, n, sg.firstId), hi = Arrays.binarySearch(id, 0, n, sg.lastId);
            for (int k = lo; k <= hi; k++) if (!r.phone[k] && TimeLine.valid(r.anchor[k], nowUnix)) a.add(new long[] {ds[k], r.anchor[k], 0, id[k]});
            for (long sid : sg.strayIds) {
                int k = Arrays.binarySearch(id, 0, n, sid);
                a.add(new long[] {ds[k], r.anchor[k], 1, id[k]});
            }
            Collections.sort(a, ORDER);
            for (int k = Math.max(0, a.size() - 2); k < a.size(); k++) st.tail.add(new long[] {a.get(k)[0], a.get(k)[1], a.get(k)[2]});
        }
        r.epoch = null;
        r.anchor = null;
        r.phone = null;
        int[] seg = new int[n];
        double[] unix = new double[n];
        Arrays.fill(seg, -1);
        Arrays.fill(unix, Double.NaN);
        for (TimeLine.Segment sg : segs) {
            int lo = Arrays.binarySearch(id, 0, n, sg.firstId), hi = Arrays.binarySearch(id, 0, n, sg.lastId);
            for (int k = lo; k <= hi; k++) seg[k] = sg.no;
            int[] stray = new int[sg.strayIds.size()];
            for (int s = 0; s < stray.length; s++) {
                stray[s] = Arrays.binarySearch(id, 0, n, sg.strayIds.get(s));
                seg[stray[s]] = sg.no;
            }
            if (sg.anchors.isEmpty()) continue;
            List<long[]> regions = TimeLine.regions(sg);
            for (int k = lo; k <= hi; k++) unix[k] = dateIn(regions, ds[k], unix[k]);
            for (int k : stray) unix[k] = dateIn(regions, ds[k], unix[k]);
        }
        for (int k = 0; k < n; k++) {
            if (unix[k] > ceil) st.clampMin = Math.min(st.clampMin, unix[k]);
            unix[k] = limit(unix[k], nowUnix);
            if (Double.isNaN(unix[k])) continue;
            st.dated++;
            if (!(unix[k] >= st.dmin)) st.dmin = unix[k];
            if (!(unix[k] <= st.dmax)) st.dmax = unix[k];
        }
        st.lastId = n == 0 ? 0 : id[n - 1];
        st.now = nowUnix;
        st.total = n;
        Dated d = new Dated();
        d.segs = segs;
        d.seg = seg;
        d.unix = unix;
        d.state = st;
        return d;
    }

    /** The 2015 and 6 hour limits: an event cannot happen well after it was downloaded, nor before 2015. */
    public static double limit(double unix, long nowUnix) {
        return unix > nowUnix + 6 * 3600 || unix < EARLIEST ? Double.NaN : unix;
    }

    /**
     * Date of {@code ds} from the region that holds it ({lo, hi, anchorDs, anchorUnix}, sorted, hi exclusive),
     * computed as the SQL did; {@code keep} when no region holds it.
     */
    public static double dateIn(List<long[]> regions, long ds, double keep) {
        int lo = 0, hi = regions.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (regions.get(mid)[0] <= ds) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best < 0) return keep;
        long[] r = regions.get(best);
        return ds < r[1] ? r[3] + (ds - r[2]) / 10.0 : keep;
    }

    // ------------------------------------------------------------------------------ the step after a sync

    /** A phone row's place: the segment it gets (or -1), or {@link #TWO} when it sits in one segment and dates another. */
    static final int TWO = -2;

    /**
     * The incremental run, worked out from the state and the rows read. {@link #full} non null: do the full run
     * instead. Otherwise the caller dates again the old rows of segment {@link #lastNo} with {@code ds >= }
     * {@link #fromDs} by {@link #dateOld}, the new rows by {@link #newSeg} and {@link #newUnix}, passes every row it
     * dated to {@link #count}, and stores {@link #next}.
     */
    public static final class Step {
        public String full;
        public final State old;
        public final long now;
        /** Number of the old newest segment, whose old rows from {@link #fromDs} on get new dates. */
        public int lastNo;
        /** Long.MAX_VALUE when no old row changes. */
        public long fromDs = Long.MAX_VALUE;
        /** Per new row: segment number or -1, and the date before the limits (NaN when none). */
        public int[] newSeg;
        public double[] newUnix;
        List<Summary> segs;
        List<long[]> lastRegions;
        boolean lastAnchored;
        List<long[]> tail;
        long futMin, validMax, newRows;
        double clampMin;
        // the count
        long datedDelta, datedBefore;
        double nmin = Double.NaN, nmax = Double.NaN;
        boolean lostMin, lostMax;

        Step(State old, long now) {
            this.old = old;
            this.now = now;
        }

        Step fallBack(String why) {
            full = why;
            return this;
        }

        /** Date before the limits of an old row of segment {@link #lastNo} with {@code ds >= fromDs}. */
        public double dateOld(long ds) {
            return lastAnchored ? dateIn(lastRegions, ds, Double.NaN) : Double.NaN;
        }

        /**
         * One row dated by this step: its stored values, its new segment and date before the limits. Returns the date to
         * store (after the limits).
         */
        public double count(double oldUnix, boolean isNew, double unix) {
            if (unix > now + 6 * 3600) clampMin = Math.min(clampMin, unix);
            double u = limit(unix, now);
            boolean was = !Double.isNaN(oldUnix), is = !Double.isNaN(u);
            if (isNew && was) datedBefore++;
            datedDelta += (is ? 1 : 0) - (was ? 1 : 0);
            if (is) {
                if (!(u >= nmin)) nmin = u;
                if (!(u <= nmax)) nmax = u;
            }
            if (!isNew && was && Double.doubleToLongBits(oldUnix) != Double.doubleToLongBits(u)) {
                if (oldUnix == old.dmin) lostMin = true;
                if (oldUnix == old.dmax) lostMax = true;
            }
            return u;
        }

        /** Dated rows before this step (the stored ones). */
        public long datedBefore() {
            return old.dated + datedBefore;
        }

        /** True when the earliest or latest date must be read from the table (the row that held it changed). */
        public boolean needsRange() {
            long dated = old.dated + datedDelta;
            if (dated == 0) return false;
            boolean minKnown = !lostMin || nmin <= old.dmin, maxKnown = !lostMax || nmax >= old.dmax;
            return !(minKnown && maxKnown);
        }

        /**
         * The state after this step. {@code range} is {min, max} read from the table when {@link #needsRange} said so,
         * else null.
         */
        public State next(long lastId, long anchorRows, double[] range) {
            State s = new State();
            s.lastId = Math.max(lastId, old.lastId);
            s.now = now;
            s.total = old.total + newRows;
            s.dated = old.dated + datedDelta;
            s.anchorRows = anchorRows;
            if (s.dated > 0) {
                if (range != null) {
                    s.dmin = range[0];
                    s.dmax = range[1];
                } else {
                    s.dmin = old.dated == 0 ? nmin : Double.isNaN(nmin) ? old.dmin : Math.min(old.dmin, nmin);
                    s.dmax = old.dated == 0 ? nmax : Double.isNaN(nmax) ? old.dmax : Math.max(old.dmax, nmax);
                }
            }
            s.futMin = futMin;
            s.validMax = validMax;
            s.clampMin = clampMin;
            for (Summary g : segs) s.segs.add(g);
            s.tail.addAll(tail);
            return s;
        }

        /** Segments after this step, for the report. */
        public int segments() {
            return segs.size();
        }

        public int anchoredSegments() {
            int k = 0;
            for (Summary g : segs) if (g.anchored) k++;
            return k;
        }
    }

    /** A time record added to a segment: {ds, unix, phone ? 1 : 0, id}. */
    private static final Comparator<long[]> ORDER = new Comparator<long[]>() {
        @Override
        public int compare(long[] a, long[] b) {
            // dating sorts the ring's records (by id) then the phone anchors (by id) by counter, keeping that order on ties
            if (a[0] != b[0]) return Long.compare(a[0], b[0]);
            if (a[2] != b[2]) return Long.compare(a[2], b[2]);
            return Long.compare(a[3], b[3]);
        }
    };

    /**
     * The step after a sync. {@code fresh}: every row with an id above the state's {@code lastId}. {@code phones}: the
     * stored time_sync rows of ids from the first id of the state's newest segment up to its {@code lastId} (only the
     * phone anchors among them are used). Reads nothing else.
     */
    public static Step step(State s, long nowUnix, Rows fresh, Rows phones) {
        Step st = new Step(s, nowUnix);
        if (s.segs.isEmpty()) return st.fallBack("no boot segment yet");
        String moved = s.changesAt(nowUnix);
        if (moved != null) return st.fallBack(moved);
        long ceil = nowUnix + 6 * 3600;
        st.futMin = s.futMin;
        st.validMax = s.validMax;
        st.clampMin = s.clampMin;
        st.newRows = fresh.n;
        // the segments, the walk of TimeLine.build carried on from the newest old one
        List<Summary> segs = new ArrayList<Summary>();
        for (Summary g : s.segs) segs.add(g.copy());
        int last = segs.size() - 1;
        Summary cur = segs.get(last);
        int[] segOf = new int[fresh.n];
        for (int i = 0; i < fresh.n; i++) {
            segOf[i] = -1;
            if (fresh.phone[i]) continue;
            if (fresh.id[i] <= s.lastId) return st.fallBack("rows out of order");
            if (fresh.epoch[i] != cur.epoch || fresh.ds[i] + TimeLine.RESET_SLACK_DS < cur.maxDs) {
                cur = new Summary();
                cur.no = segs.size() + 1;
                cur.firstId = fresh.id[i];
                cur.epoch = fresh.epoch[i];
                segs.add(cur);
            }
            cur.lastId = fresh.id[i];
            cur.events++;
            cur.minDs = Math.min(cur.minDs, fresh.ds[i]);
            cur.maxDs = Math.max(cur.maxDs, fresh.ds[i]);
            segOf[i] = segs.size() - 1;
        }
        st.segs = segs;
        st.lastNo = last + 1;
        List<List<long[]>> added = new ArrayList<List<long[]>>();
        for (int k = 0; k < segs.size(); k++) added.add(new ArrayList<long[]>());
        // the ring's own time records among the new rows, in their segment
        for (int i = 0; i < fresh.n; i++) {
            long a = fresh.anchor[i];
            if (a > 1_420_000_000L) {
                if (a <= ceil) st.validMax = Math.max(st.validMax, a);
                else st.futMin = Math.min(st.futMin, a);
            }
            if (!fresh.phone[i] && TimeLine.valid(a, nowUnix)) added.get(segOf[i]).add(new long[] {fresh.ds[i], a, 0, fresh.id[i]});
        }
        // the stored phone anchors of the newest segment: none may change the segment it dates or sits in
        for (int i = 0; i < phones.n; i++) {
            if (!phones.phone[i]) continue;
            long id = phones.id[i];
            if (id < s.segs.get(last).firstId || id > s.lastId) return st.fallBack("time records out of range");
            boolean ok = TimeLine.valid(phones.anchor[i], nowUnix);
            int mOld = ok ? match(s.segs, id, phones.epoch[i], phones.ds[i]) : -1, mNew = ok ? match(segs, id, phones.epoch[i], phones.ds[i]) : -1;
            int eOld = place(rangeOf(s.segs, id), mOld), eNew = place(rangeOf(segs, id), mNew);
            if (eOld == TWO || eNew == TWO) return st.fallBack("a phone time record sits between two boots");
            if (eOld != eNew) return st.fallBack("a phone time record changes its boot");
            if (mOld == mNew) continue;
            if (mOld >= 0 || mNew != last) return st.fallBack("a phone time record moves to another boot");
            added.get(last).add(new long[] {phones.ds[i], phones.anchor[i], 1, id});
        }
        // the new phone anchors
        int[] placeOf = new int[fresh.n];
        for (int i = 0; i < fresh.n; i++) {
            if (!fresh.phone[i]) continue;
            long id = fresh.id[i];
            if (id <= s.lastId) return st.fallBack("rows out of order");
            int m = TimeLine.valid(fresh.anchor[i], nowUnix) ? match(segs, id, fresh.epoch[i], fresh.ds[i]) : -1;
            int e = place(rangeOf(segs, id), m);
            if (e == TWO) return st.fallBack("a phone time record sits between two boots");
            if (m >= 0 && m < last) return st.fallBack("a phone time record for an earlier boot");
            if (m >= 0) added.get(m).add(new long[] {fresh.ds[i], fresh.anchor[i], 1, id});
            placeOf[i] = e;
        }
        // the newest old segment: its new records must follow its old ones
        List<long[]> addLast = added.get(last);
        Collections.sort(addLast, ORDER);
        long[] lastOld = s.tail.isEmpty() ? null : s.tail.get(s.tail.size() - 1);
        if (lastOld != null && !addLast.isEmpty()) {
            long[] a = addLast.get(0);
            // on an equal counter a new row sorts after the old ones of its kind (higher id), a ring record before a phone anchor
            boolean after = a[0] > lastOld[0] || (a[0] == lastOld[0] && a[3] > s.lastId && a[2] >= lastOld[2]);
            if (!after) return st.fallBack("a time record inside the stored part of the newest boot");
        }
        // regions from the midpoint of its last two old records on are those of the full list (see the class comment)
        long from = s.tail.size() >= 2 ? (s.tail.get(0)[0] + s.tail.get(1)[0]) / 2 : Long.MIN_VALUE / 4;
        TimeLine.Segment pseudo = new TimeLine.Segment();
        List<long[]> lastList = new ArrayList<long[]>();
        for (long[] a : s.tail) lastList.add(a);
        lastList.addAll(addLast);
        for (long[] a : lastList) pseudo.anchors.add(new long[] {a[0], a[1]});
        st.lastRegions = TimeLine.regions(pseudo);
        st.lastAnchored = !pseudo.anchors.isEmpty();
        segs.get(last).anchored = st.lastAnchored;
        if (!addLast.isEmpty()) st.fromDs = from;
        // the other new segments: all their rows are new
        List<List<long[]>> regions = new ArrayList<List<long[]>>();
        for (int k = 0; k < segs.size(); k++) regions.add(null);
        regions.set(last, st.lastRegions);
        List<long[]> newTail = new ArrayList<long[]>();
        for (int k = last + 1; k < segs.size(); k++) {
            List<long[]> add = added.get(k);
            Collections.sort(add, ORDER);
            TimeLine.Segment sg = new TimeLine.Segment();
            for (long[] a : add) sg.anchors.add(new long[] {a[0], a[1]});
            regions.set(k, TimeLine.regions(sg));
            segs.get(k).anchored = !add.isEmpty();
            if (k == segs.size() - 1) newTail = add;
        }
        if (segs.size() - 1 == last) newTail = lastList;
        st.tail = new ArrayList<long[]>();
        for (int k = Math.max(0, newTail.size() - 2); k < newTail.size(); k++) st.tail.add(new long[] {newTail.get(k)[0], newTail.get(k)[1], newTail.get(k)[2]});
        // the new rows
        st.newSeg = new int[fresh.n];
        st.newUnix = new double[fresh.n];
        for (int i = 0; i < fresh.n; i++) {
            int g = fresh.phone[i] ? placeOf[i] : segOf[i];
            st.newSeg[i] = g < 0 ? -1 : segs.get(g).no;
            st.newUnix[i] = Double.NaN;
            if (g < 0) continue;
            if (g == last && s.tail.size() >= 2 && fresh.ds[i] < from) return st.fallBack("an event of the newest boot before its last time records");
            if (segs.get(g).anchored) st.newUnix[i] = dateIn(regions.get(g), fresh.ds[i], Double.NaN);
        }
        return st;
    }

    /** {@link #TWO} for a phone row inside segment {@code range} that dates segment {@code match}, else the segment it gets. */
    static int place(int range, int match) {
        if (range >= 0 && match >= 0 && range != match) return TWO;
        return range >= 0 ? range : match;
    }

    /** Index of the segment whose id range holds {@code id}, or -1. */
    static int rangeOf(List<Summary> segs, long id) {
        int lo = 0, hi = segs.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (segs.get(mid).firstId <= id) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best >= 0 && id <= segs.get(best).lastId ? best : -1;
    }

    /** As {@link TimeLine#build} matches a valid phone anchor: the newest segment at or before it whose range covers it. */
    static int match(List<Summary> segs, long id, long epoch, long ds) {
        for (int k = segs.size() - 1; k >= 0; k--) {
            Summary g = segs.get(k);
            if (g.firstId > id) continue;
            if (epoch == g.epoch && ds >= g.minDs - TimeLine.RESET_SLACK_DS && ds <= g.maxDs + TimeLine.RESET_SLACK_DS) return k;
        }
        return -1;
    }
}
