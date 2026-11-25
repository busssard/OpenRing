package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of the Sleep page's night timeline with its moment and kind (code health of issue #380, the Experiment view's
 * point 1): the clock and the text it shows, the unix time it stands for and what kind of row it is, so rows of other
 * kinds (sleep sounds, phase 1c) can be sorted in by time. {@link WakeUps#rows} gives the night's own rows.
 */
public final class TimelineRow {
    /** Kinds of rows: the night's own ({@link WakeUps#rows}), a left out time next to it, a sleep sound or listening. */
    public static final String NIGHT = "night", LEFT_OUT = "left_out", SOUND = "sound", LISTEN = "listen";

    /** The moment the row stands for, unix seconds. */
    public final double at;
    public final String kind, clock, text;

    public TimelineRow(double at, String kind, String clock, String text) {
        this.at = at;
        this.kind = kind;
        this.clock = clock;
        this.text = text;
    }

    /** {clock, text}, as the timeline showed its rows before they had a time and kind. */
    public String[] pair() {
        return new String[] {clock, text};
    }

    /** Each row as {clock, text}. */
    public static List<String[]> pairs(List<TimelineRow> rows) {
        List<String[]> out = new ArrayList<String[]>(rows.size());
        for (TimelineRow r : rows) out.add(r.pair());
        return out;
    }

    /**
     * {@code base} with {@code extra} sorted in by time: the extra rows in their time order (equal times keep their order),
     * each one before the first base row that comes later, so the base rows keep their exact order and a row at the same
     * moment as a base row comes after it. {@code extra} null or empty gives a copy of {@code base}. Neither list changes.
     */
    public static List<TimelineRow> merge(List<TimelineRow> base, List<TimelineRow> extra) {
        List<TimelineRow> out = new ArrayList<TimelineRow>(base.size() + (extra == null ? 0 : extra.size()));
        if (extra == null || extra.isEmpty()) {
            out.addAll(base);
            return out;
        }
        List<TimelineRow> more = new ArrayList<TimelineRow>(extra);
        // a stable sort: equal times keep the order given
        java.util.Collections.sort(more, new java.util.Comparator<TimelineRow>() {
            @Override
            public int compare(TimelineRow a, TimelineRow b) {
                return Double.compare(a.at, b.at);
            }
        });
        int k = 0;
        for (TimelineRow b : base) {
            while (k < more.size() && more.get(k).at < b.at) out.add(more.get(k++));
            out.add(b);
        }
        while (k < more.size()) out.add(more.get(k++));
        return out;
    }
}
