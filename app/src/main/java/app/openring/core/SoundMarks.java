package app.openring.core;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import app.openring.core.analysis.ClockChange;
import app.openring.core.analysis.TimelineRow;
import app.openring.core.analysis.WakeUps;

/**
 * Sleep sounds on the night's timeline (issue #380, phase 1c): the Sounds strip under the stages, its touch readout and the
 * rows the night timeline gets. Plain Java, so the JVM tests run it; the screens only draw what it decides. Every time is
 * shown in the zone of its night and rounded to the nearest minute ({@link WakeUps#clock}), as the timeline shows times.
 */
public final class SoundMarks {
    private SoundMarks() {}

    /** Why the phone was not listening ({@code sound_gaps.why}; {@code data/Sounds} uses the same values). */
    public static final String GAP_SILENCE = "silence", GAP_SYSTEM = "system";
    /** A time not listening this long (seconds) or longer gets its own row in the timeline, as a wake up of a minute. */
    public static final double GAP_ROW_S = 60;
    /** A touch reads every mark this close to the finger (dp, the screens turn it into seconds). */
    public static final float REACH_DP = 8;

    public static final String LABEL = "Sounds";
    public static final String STRIP_NOTE = "Sounds the phone kept: touch a mark to read it and play it. Shaded: the phone was not listening.";
    public static final String TIMELINE_NOTE = "Sounds the phone kept are in yellow; play them on the strip or the Sleep sounds card.";
    public static final String STARTED = "Started listening for sleep sounds", STOPPED = "Stopped listening";

    /** A time the phone was not listening. */
    public static final class Gap {
        public final double start, stop;
        /** {@link #GAP_SILENCE}, {@link #GAP_SYSTEM} or null when unknown. */
        public final String why;

        public Gap(double start, double stop, String why) {
            this.start = start;
            this.stop = stop;
            this.why = why;
        }

        public boolean holds(double t) {
            return t >= start && t <= stop;
        }
    }

    /** HH:mm of {@code unix} in {@code zone}, rounded to the nearest minute (the timeline's clock, issue #138). */
    static String clock(double unix, ZoneId zone) {
        return WakeUps.clock(unix, zone);
    }

    /** The kind in a sentence: "snoring", "cough like sound", "talking or other sound". */
    public static String kindLower(String kind) {
        return SleepSounds.kindName(kind).toLowerCase(Locale.ROOT);
    }

    /** Why a gap was not listening, for the end of a sentence, or null when unknown. */
    static String reason(String why) {
        if (GAP_SILENCE.equals(why)) return "another app used the microphone";
        if (GAP_SYSTEM.equals(why)) return "the phone lost the audio";
        return null;
    }

    /**
     * A mark read by touch: "02:14, talking or other sound, 6 s", "02:12 to 02:26, snoring, 14 min", with " (sound deleted)"
     * when its sound is gone. In the hour that happens twice its start gets "(first time)" or "(second time)" (issue #97).
     */
    public static String markTip(SleepSounds.Mark m, ZoneId zone) {
        String at = clock(m.start, zone) + ClockChange.repeatNote(zone, WakeUps.roundMin(m.start));
        // a stretch from its start to its stop, when they are different minutes
        boolean range = app.openring.core.analysis.SoundDetector.SNORE.equals(m.kind) && !clock(m.stop, zone).equals(clock(m.start, zone));
        String head = range ? at + " to " + clock(m.stop, zone) : at;
        return head + ", " + kindLower(m.kind) + ", " + SleepSounds.length(m.stop - m.start) + (m.sound ? "" : " " + SleepSounds.GONE);
    }

    /** "04:10 to 04:22, not listening: another app used the microphone" (or ": the phone lost the audio"). */
    public static String gapTip(Gap g, ZoneId zone) {
        String r = reason(g.why);
        return clock(g.start, zone) + " to " + clock(g.stop, zone) + ", not listening" + (r == null ? "" : ": " + r);
    }

    /**
     * The marks a touch at {@code t} reads: each one whose time is within {@code reachS} seconds of it, the shortest first (a
     * cough inside a snoring stretch comes before the stretch), then by start.
     */
    public static List<SleepSounds.Mark> pick(List<SleepSounds.Mark> marks, double t, double reachS) {
        List<SleepSounds.Mark> out = new ArrayList<SleepSounds.Mark>();
        for (SleepSounds.Mark m : marks) if (t >= m.start - reachS && t <= Math.max(m.start, m.stop) + reachS) out.add(m);
        Collections.sort(out, new Comparator<SleepSounds.Mark>() {
            @Override
            public int compare(SleepSounds.Mark a, SleepSounds.Mark b) {
                int c = Double.compare(a.stop - a.start, b.stop - b.start);
                return c != 0 ? c : Double.compare(a.start, b.start);
            }
        });
        return out;
    }

    /**
     * The Sounds strip's readout at {@code t}: the marks within reach (one line each), else the gap that holds it, else
     * "Not listening yet, started at 23:05" before listening, "Listening stopped at 06:55" (": Android ended it" when
     * {@code ended} is system) after it, and "03:00, listening, no sound kept" in between. {@code stop} NaN: not known yet.
     */
    public static String tip(List<SleepSounds.Mark> marks, List<Gap> gaps, double start, double stop, String ended, double t, double reachS,
            ZoneId zone) {
        List<SleepSounds.Mark> near = pick(marks, t, reachS);
        if (!near.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (SleepSounds.Mark m : near) b.append(b.length() == 0 ? "" : "\n").append(markTip(m, zone));
            return b.toString();
        }
        for (Gap g : gaps) if (g.holds(t)) return gapTip(g, zone);
        if (t < start) return "Not listening yet, started at " + clock(start, zone);
        if (!Double.isNaN(stop) && t > stop) return "Listening stopped at " + clock(stop, zone) + (SleepSounds.ENDED_SYSTEM.equals(ended) ? ": Android ended it" : "");
        return clock(t, zone) + ", listening, no sound kept";
    }

    /**
     * The spans the phone listened inside the axis {@code from} to {@code to}: from {@code start} to {@code stop} (to the axis'
     * end while unknown, NaN) without the gaps, in time order; each {start, stop}. Empty when it never listened there.
     */
    public static List<double[]> listening(double start, double stop, List<Gap> gaps, double from, double to) {
        List<double[]> out = new ArrayList<double[]>();
        double a = Math.max(from, start), b = Math.min(to, Double.isNaN(stop) ? to : stop);
        if (!(b > a)) return out;
        List<Gap> sorted = new ArrayList<Gap>(gaps);
        Collections.sort(sorted, new Comparator<Gap>() {
            @Override
            public int compare(Gap x, Gap y) {
                return Double.compare(x.start, y.start);
            }
        });
        double at = a;
        for (Gap g : sorted) {
            if (g.stop <= at || g.start >= b) continue;
            if (g.start > at) out.add(new double[] {at, g.start});
            at = Math.max(at, g.stop);
            if (at >= b) break;
        }
        if (at < b) out.add(new double[] {at, b});
        return out;
    }

    /** Marks that end before the sleep window starts at {@code s} (not drawn on the strip, which shows the window). */
    public static int before(List<SleepSounds.Mark> marks, double s) {
        int n = 0;
        for (SleepSounds.Mark m : marks) if (Math.max(m.start, m.stop) < s) n++;
        return n;
    }

    /** Marks that start after the sleep window ends at {@code e}. */
    public static int after(List<SleepSounds.Mark> marks, double e) {
        int n = 0;
        for (SleepSounds.Mark m : marks) if (m.start > e) n++;
        return n;
    }

    /** "2 sounds before the sleep window and 1 after it are in the night timeline.", null when every mark is in the window. */
    public static String outsideLine(int before, int after) {
        if (before <= 0 && after <= 0) return null;
        String verb = before + after == 1 ? " is" : " are";
        String where;
        if (before > 0 && after > 0) where = Texts.count(before, "sound", "sounds") + " before the sleep window and " + after + " after it";
        else if (before > 0) where = Texts.count(before, "sound", "sounds") + " before the sleep window";
        else where = Texts.count(after, "sound", "sounds") + " after the sleep window";
        return where + verb + " in the night timeline.";
    }

    /**
     * The night timeline's rows of a night listened (started {@code start}, last heard {@code stop}, NaN when unknown, ended
     * for {@code ended}, null while it runs): "Started listening for sleep sounds", a row per mark ("Snoring, 14 min",
     * "Talking or other sound, 4 s (sound deleted)"), a row per time not listening of a minute or more ("Not listening for 12
     * min: another app used the microphone") and "Stopped listening" (": Android ended it"). Sorted in with
     * {@link TimelineRow#merge}.
     */
    public static List<TimelineRow> rows(List<SleepSounds.Mark> marks, List<Gap> gaps, double start, double stop, String ended, ZoneId zone) {
        List<TimelineRow> out = new ArrayList<TimelineRow>();
        out.add(new TimelineRow(start, TimelineRow.LISTEN, clock(start, zone), STARTED));
        for (SleepSounds.Mark m : marks) {
            out.add(new TimelineRow(m.start, TimelineRow.SOUND, clock(m.start, zone),
                    SleepSounds.kindName(m.kind) + ", " + SleepSounds.length(m.stop - m.start) + (m.sound ? "" : " " + SleepSounds.GONE)));
        }
        for (Gap g : gaps) {
            if (g.stop - g.start < GAP_ROW_S) continue;
            String r = reason(g.why);
            out.add(new TimelineRow(g.start, TimelineRow.LISTEN, clock(g.start, zone),
                    "Not listening for " + Math.max(1, WakeUps.shownMinutes(g.start, g.stop)) + " min" + (r == null ? "" : ": " + r)));
        }
        if (ended != null && !Double.isNaN(stop)) {
            out.add(new TimelineRow(stop, TimelineRow.LISTEN, clock(stop, zone),
                    STOPPED + (SleepSounds.ENDED_SYSTEM.equals(ended) ? ": Android ended it" : "")));
        }
        return out;
    }

    /** Times not listening shorter than {@link #GAP_ROW_S}: drawn on the strip, counted under the timeline. */
    public static int shortGaps(List<Gap> gaps) {
        int n = 0;
        for (Gap g : gaps) if (g.stop - g.start < GAP_ROW_S) n++;
        return n;
    }

    /** "Also 3 short times not listening (under a minute), shaded on the Sounds strip.", null for none. */
    public static String shortGapsLine(int n) {
        if (n <= 0) return null;
        return "Also " + Texts.count(n, "short time", "short times") + " not listening (under a minute), shaded on the Sounds strip.";
    }
}
