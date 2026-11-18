package app.openring.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The order of Today's cards (owner's wish of 17 November: "The sentence is the main input for the human"). Whatever the
 * devices, the scores come first with the battery folded into one line per device under them, then Your day with its sentence
 * and its line, so both are on the first screen; everything else follows, nothing is left out. Plain Java so the JVM tests
 * cover the order for the ring, the watch and the phone.
 */
public final class TodayCards {
    private TodayCards() {}

    /** The score dials (the ring's, or the watch's or the phone's) with the battery lines folded under them. */
    public static final String SCORES = "scores";
    /** Your day: the sentence with the day's facts and the line (or, with a ring and no data yet, what to do). */
    public static final String DAY = "day";
    /** Without a ring: where the scores come from, in one short line that folds open, and the ways to add a device. */
    public static final String DEVICE_NOTE = "device_note";
    /** A time left out because someone else wore the ring (issue #254). */
    public static final String LEFT_OUT = "left_out";
    /** Recent badges (issue #265). */
    public static final String BADGES = "badges";
    /** The watch was chosen at the start but is not added yet. */
    public static final String FINISH_WATCH = Start.FINISH_WATCH;
    /** With a ring: Key metrics and the symptom radar; without: the watch's or the phone's day. */
    public static final String RING_METRICS = Start.RING_METRICS, WATCH_DAY = Start.WATCH_DAY, PHONE_DAY = Start.PHONE_DAY;
    /** Live heart rate (a ring). */
    public static final String LIVE = Start.LIVE;
    /** The period tracker's card. */
    public static final String PERIOD = "period";
    /**
     * Life factors (issue #292): "What was today like?" with one tap chips for the day shown, right after Your day, so the
     * measured first screen (the scores and Your day) does not move; for every device, as the tags are the person's own.
     */
    public static final String LIFE = "life";

    /**
     * Today's cards for {@code h}, top to bottom. {@code badges}: there are badges of the last 7 days; {@code leftOut}: part of
     * the day is left out; {@code period}: the tracker is shown.
     */
    public static List<String> of(Start.Has h, boolean badges, boolean leftOut, boolean period) {
        // the device dependent cards as before (issue #373); Find my ring stays a small button in the scores card
        List<String> dev = Start.cards(Start.TODAY, h);
        List<String> out = new ArrayList<String>();
        out.add(SCORES);
        out.add(DAY);
        out.add(LIFE);
        if (!h.ring) out.add(DEVICE_NOTE);
        if (leftOut) out.add(LEFT_OUT);
        if (dev.contains(FINISH_WATCH)) out.add(FINISH_WATCH);
        if (badges) out.add(BADGES);
        for (String k : dev) {
            if (k.equals(Start.SCORES) || k.equals(Start.DEVICE_SCORES) || k.equals(Start.FIND) || k.equals(FINISH_WATCH)) continue;
            out.add(k);
        }
        if (period) out.add(PERIOD);
        return out;
    }

    /** Where Your day stands: always right after the scores, so its sentence and line are on the first screen. */
    public static int dayIndex(List<String> cards) {
        return cards.indexOf(DAY);
    }
}
