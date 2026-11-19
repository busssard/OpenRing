package app.openring.core.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Sleep sounds (issue #380, phase 1a): finds loud moments in the microphone's audio of a night, labels them, and says which
 * short stretches of audio are worth keeping as clips. Pure Java, deterministic, no Android: the listening service (phase 1b)
 * feeds it the samples as they come, the JVM tests feed it made up audio.
 *
 * <p>Input: 16 kHz mono, 16 bit samples, in pieces of any length ({@link #feed}); they are worked through in frames of
 * {@link #FRAME} samples (32 ms). Times are unix seconds: the night's start plus the samples fed so far.
 *
 * <p>How it decides (the numbers of the design round on #380):
 * <ul>
 * <li>Loudness per frame in dB of full scale (the frame's RMS against 32768; digital silence counts as {@link #MIN_DB}).</li>
 * <li>The noise floor is the 20th percentile of the frames of the last 60 s (0.5 dB steps); frames of digital silence are
 * left out of it, so a microphone taken by another app cannot pull it down.</li>
 * <li>A sound starts when {@link #START_FRAMES} frames in a row lie more than {@link #ABOVE_DB} above the floor (at its first
 * frame), and ends after {@link #END_S} below it (at the end of its last loud frame).</li>
 * <li>Cough like ({@link #COUGH}): shorter than {@link #COUGH_MAX_S}, its loudest frame among the first {@link #SHARP_FRAMES},
 * and more than {@link #COUGH_ZC} zero crossings per frame on average (noise like; speech and snoring have 10 to 40).</li>
 * <li>Snoring ({@link #SNORE}): {@link #SNORE_MIN} or more sounds, each starting {@link #SNORE_GAP_MIN} to
 * {@link #SNORE_GAP_MAX} s after the one before, the shorter at least half as long as the longer, peaks within
 * {@link #SNORE_PEAK_DB}; grouped into one stretch. A sound of {@link #SNORE_GAP_MAX} s or longer is never a snore (the next
 * one could not start within the rhythm), so its kind is known as soon as it ends.</li>
 * <li>Everything else is {@link #TALK}, "talking or other sound": phase 1 does not tell talking from a door or a dog.</li>
 * <li>A gap ({@link Gap}): {@link #GAP_S} or more of exact digital silence (every sample 0), what Android delivers while
 * another app holds the microphone.</li>
 * </ul>
 *
 * <p>Output:
 * <ul>
 * <li>{@link #marks()}: what the night's timeline shows, each with its final kind: every single sound that is not part of a
 * snoring stretch, and each stretch as one mark (start of its first snore to the end of its last).</li>
 * <li>Clips: for each sound, the window from {@link #BEFORE_S} before it to {@link #AFTER_S} after it, at most
 * {@link #CLIP_MAX_S} (from its start; never before the night's start). A clip is ready once its window has passed
 * ({@link #takeReady}), so the caller needs to keep only the last {@link #CLIP_MAX_S} s of audio (plus one piece) to save it.
 * At most {@link #MAX_CLIPS} clips are kept: when full, a louder one replaces the quietest. A stretch keeps only the clip of
 * its loudest snore. A clip given up after it was ready comes back from {@link #takeDropped}, so its audio can be deleted.</li>
 * </ul>
 */
public final class SoundDetector {
    /** Samples per second and per frame (32 ms). */
    public static final int RATE = 16000, FRAME = 512;
    public static final double FRAME_S = FRAME / (double) RATE;
    /** Loudness of a frame of digital silence, and the floor of the scale (dB of full scale). */
    public static final double MIN_DB = -120;
    /** The floor: this percentile of the frames of the last {@link #FLOOR_S} seconds. */
    public static final double FLOOR_PERCENTILE = 0.2, FLOOR_S = 60;
    /** Frames of history the floor needs before anything counts as a sound (0.5 s). */
    public static final int FLOOR_MIN_FRAMES = 16;
    /** A frame is loud this many dB above the floor; a sound starts after this many loud frames in a row. */
    public static final double ABOVE_DB = 10;
    public static final int START_FRAMES = 3;
    /** A sound ends after this long below the threshold. */
    public static final double END_S = 1.0;
    /** Cough like: shorter than this, loudest frame among the first ones, more zero crossings per frame than this. */
    public static final double COUGH_MAX_S = 1.2, COUGH_ZC = 150;
    public static final int SHARP_FRAMES = 5;
    /** Snoring: at least this many sounds, starts this many seconds apart, peaks this close. */
    public static final int SNORE_MIN = 3;
    public static final double SNORE_GAP_MIN = 2, SNORE_GAP_MAX = 7, SNORE_PEAK_DB = 6, SNORE_LENGTH_RATIO = 0.5;
    /** The clip window around a sound, and its longest length. */
    public static final double BEFORE_S = 5, AFTER_S = 3, CLIP_MAX_S = 20;
    /** At most this many clips a night. */
    public static final int MAX_CLIPS = 60;
    /** Digital silence this long or longer is a gap. */
    public static final double GAP_S = 2;

    /** The kinds as they are stored ({@code sound_clips.kind}). */
    public static final String SNORE = "snore", COUGH = "cough", TALK = "talk";

    private static final int BINS = 241; // 0.5 dB steps from MIN_DB to 0
    private static final int WINDOW = (int) Math.round(FLOOR_S / FRAME_S);
    private static final int END_FRAMES = (int) Math.ceil(END_S / FRAME_S - 1e-9);
    private static final int GAP_SAMPLES = (int) Math.round(GAP_S * RATE);

    /** A clip: the window of audio to keep, and the sound it was made for. */
    public static final class Clip {
        /** The window (unix seconds) and the loudest frame of its sound inside it (dB of full scale). */
        public final double start, stop, peak;
        /** The start of the sound it was made for. */
        public final double sound;

        Clip(double start, double stop, double peak, double sound) {
            this.start = start;
            this.stop = stop;
            this.peak = peak;
            this.sound = sound;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "clip %.3f-%.3f peak %.1f for %.3f", start, stop, peak, sound);
        }
    }

    /** A mark on the night: one sound, or a snoring stretch. */
    public static final class Mark {
        public final double start, stop, peak;
        public final String kind;
        /** Sounds in it: 1, or the snores of a stretch. */
        public final int sounds;
        private final Sound single;
        private final Run run;

        Mark(Sound s) {
            start = s.start;
            stop = s.stop;
            peak = s.peak;
            kind = s.kind;
            sounds = 1;
            single = s;
            run = null;
        }

        Mark(Run r) {
            start = r.members.get(0).start;
            stop = r.members.get(r.members.size() - 1).stop;
            double p = MIN_DB;
            for (Sound s : r.members) p = Math.max(p, s.peak);
            peak = p;
            kind = SNORE;
            sounds = r.members.size();
            single = null;
            run = r;
        }

        /** Minutes from the start to the end (a snoring stretch's minutes). */
        public double minutes() {
            return (stop - start) / 60.0;
        }

        /** The clip kept for this mark now, or null (given up for louder ones, or not ready yet). */
        public Clip clip() {
            Sound s = single != null ? single : run.best;
            return s == null || s.clip == null || !s.kept ? null : s.clip;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%s %.3f-%.3f peak %.1f x%d", kind, start, stop, peak, sounds);
        }
    }

    /** A stretch of digital silence. */
    public static final class Gap {
        public final double start, stop;

        Gap(double start, double stop) {
            this.start = start;
            this.stop = stop;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "gap %.4f-%.4f", start, stop);
        }
    }

    /** One sound while it is worked out. */
    private static final class Sound {
        double start, stop, peak = MIN_DB;
        int peakFrame, loudFrames;
        long zc;
        boolean ended;
        String kind;
        double clipStart;
        Clip clip;
        boolean kept;
        Run run;
    }

    /** Sounds that may be snoring, in a row. */
    private static final class Run {
        final List<Sound> members = new ArrayList<Sound>();
        /** For a stretch (3 or more): the member whose clip is kept. */
        Sound best;
    }

    private final double origin;
    private final short[] frame = new short[FRAME];
    private int filled;
    private long frames;
    private long samples;
    private long zeroRun;
    private final int[] hist = new int[BINS];
    private final int[] window = new int[WINDOW];
    private int windowCount, windowPos;
    private double floor = Double.NaN;
    private int loudRow;
    private double candidateStart, candidatePeak = MIN_DB;
    private int candidatePeakFrame;
    private long candidateZc;
    private Sound current;
    private int below;
    private Run run;
    private boolean finished;
    /** Sounds whose clip window is still open. */
    private final List<Sound> open = new ArrayList<Sound>();
    private final List<Sound> keptClips = new ArrayList<Sound>();
    private final List<Mark> marks = new ArrayList<Mark>();
    private final List<Mark> newMarks = new ArrayList<Mark>();
    private final List<Gap> gaps = new ArrayList<Gap>();
    private final List<Clip> ready = new ArrayList<Clip>(), dropped = new ArrayList<Clip>();

    /** A detector for a night that starts at {@code start} (unix seconds), the time of the first sample. */
    public SoundDetector(double start) {
        origin = start;
    }

    /** Feeds {@code s} (all of it). */
    public void feed(short[] s) {
        feed(s, 0, s.length);
    }

    /** Feeds {@code len} samples of {@code s} from {@code off}. Nothing after {@link #finish}. */
    public void feed(short[] s, int off, int len) {
        if (finished) return;
        for (int i = off; i < off + len; i++) {
            short v = s[i];
            if (v == 0) {
                zeroRun++;
            } else {
                endZeroRun();
                zeroRun = 0;
            }
            samples++;
            frame[filled++] = v;
            if (filled == FRAME) {
                frame();
                filled = 0;
            }
        }
    }

    /**
     * The end of the night: a sound still going ends at its last loud frame, every clip window closes at the last sample, a
     * row of possible snores is decided, digital silence up to the end is a gap when long enough. Samples of an unfinished
     * frame count only for the gap. Twice changes nothing.
     */
    public void finish() {
        if (finished) return;
        finished = true;
        endZeroRun();
        zeroRun = 0;
        double now = time(frames * FRAME);
        if (current != null) endSound();
        for (Sound s : new ArrayList<Sound>(open)) closeClip(s, now);
        closeRun();
    }

    private void endZeroRun() {
        if (zeroRun >= GAP_SAMPLES) gaps.add(new Gap(time(samples - zeroRun), time(samples)));
    }

    private double time(long sample) {
        return origin + sample / (double) RATE;
    }

    private void frame() {
        long sum = 0, zc = 0;
        boolean zero = true;
        for (int i = 0; i < FRAME; i++) {
            int v = frame[i];
            sum += (long) v * v;
            if (v != 0) zero = false;
            if (i > 0 && (frame[i - 1] < 0) != (v < 0)) zc++;
        }
        double db = zero ? MIN_DB : Math.max(MIN_DB, Math.min(0, 10 * Math.log10(sum / (double) FRAME / (32768.0 * 32768.0))));
        long index = frames;
        double start = time(index * FRAME), end = time((index + 1) * FRAME);
        frames++;
        boolean loud = !zero && windowCount >= FLOOR_MIN_FRAMES && db > floor + ABOVE_DB;
        if (current == null) {
            if (loud) {
                if (loudRow == 0) {
                    candidateStart = start;
                    candidatePeak = MIN_DB;
                    candidateZc = 0;
                }
                if (db > candidatePeak) {
                    candidatePeak = db;
                    candidatePeakFrame = loudRow;
                }
                candidateZc += zc;
                loudRow++;
                if (loudRow >= START_FRAMES) startSound(end);
            } else {
                loudRow = 0;
            }
        } else if (loud) {
            below = 0;
            current.stop = end;
            current.loudFrames++;
            current.zc += zc;
            if (db > current.peak) {
                current.peak = db;
                current.peakFrame = current.loudFrames - 1;
            }
        } else if (++below >= END_FRAMES) {
            endSound();
        }
        if (!zero) addToFloor(db);
        // clip windows that have passed, then a row of possible snores that can take nobody more
        if (!open.isEmpty()) {
            for (Sound s : new ArrayList<Sound>(open)) {
                double stop = s.clipStart + CLIP_MAX_S;
                if (s.ended) stop = Math.min(stop, s.stop + AFTER_S);
                if (end >= stop - 1e-9) closeClip(s, stop);
            }
        }
        if (run != null) {
            Sound last = run.members.get(run.members.size() - 1);
            double next = current != null ? current.start : end;
            if (next - last.start > SNORE_GAP_MAX + 1e-9) closeRun();
        }
    }

    private void startSound(double end) {
        Sound s = new Sound();
        s.start = candidateStart;
        s.stop = end;
        s.peak = candidatePeak;
        s.peakFrame = candidatePeakFrame;
        s.loudFrames = loudRow;
        s.zc = candidateZc;
        s.clipStart = Math.max(origin, s.start - BEFORE_S);
        current = s;
        below = 0;
        loudRow = 0;
        open.add(s);
    }

    private void endSound() {
        Sound s = current;
        current = null;
        below = 0;
        s.ended = true;
        double length = s.stop - s.start;
        double zcPerFrame = s.zc / (double) s.loudFrames;
        if (length < COUGH_MAX_S - 1e-9 && s.peakFrame < SHARP_FRAMES && zcPerFrame > COUGH_ZC) {
            s.kind = COUGH;
            addMark(new Mark(s));
            return;
        }
        if (length >= SNORE_GAP_MAX - 1e-9) {
            closeRun();
            s.kind = TALK;
            addMark(new Mark(s));
            return;
        }
        if (run != null && !fits(run.members.get(run.members.size() - 1), s)) closeRun();
        if (run == null) run = new Run();
        run.members.add(s);
        s.run = run;
        if (run.members.size() == SNORE_MIN) {
            // a stretch now: only the clip of its loudest snore stays
            for (Sound m : run.members) if (m.clip != null && m.kept) stretchClip(run, m);
        }
    }

    private static boolean fits(Sound a, Sound b) {
        double gap = b.start - a.start;
        double la = a.stop - a.start, lb = b.stop - b.start;
        return gap >= SNORE_GAP_MIN - 1e-9 && gap <= SNORE_GAP_MAX + 1e-9 && Math.min(la, lb) >= SNORE_LENGTH_RATIO * Math.max(la, lb) - 1e-9
                && Math.abs(a.peak - b.peak) <= SNORE_PEAK_DB + 1e-9;
    }

    private void closeRun() {
        Run r = run;
        if (r == null) return;
        run = null;
        if (r.members.size() >= SNORE_MIN) {
            for (Sound s : r.members) s.kind = SNORE;
            addMark(new Mark(r));
        } else {
            for (Sound s : r.members) {
                s.kind = TALK;
                s.run = null;
                addMark(new Mark(s));
            }
        }
    }

    private void addMark(Mark m) {
        marks.add(m);
        newMarks.add(m);
    }

    private void closeClip(Sound s, double stop) {
        open.remove(s);
        s.clip = new Clip(s.clipStart, Math.max(s.clipStart, stop), s.peak, s.start);
        if (s.run != null && s.run.members.size() >= SNORE_MIN) {
            stretchClip(s.run, s);
            return;
        }
        keep(s);
    }

    /** {@code s} (its clip ready) belongs to a stretch: the louder of it and the stretch's clip stays. */
    private void stretchClip(Run r, Sound s) {
        if (r.best == null) {
            r.best = s;
            if (!s.kept) keep(s);
            return;
        }
        if (louder(s, r.best)) {
            Sound old = r.best;
            r.best = s;
            if (!s.kept) keep(s);
            drop(old);
        } else {
            drop(s);
        }
    }

    private static boolean louder(Sound a, Sound b) {
        return a.clip.peak > b.clip.peak + 1e-9;
    }

    /** Keeps the clip of {@code s}; past {@link #MAX_CLIPS}, the quietest (the newest of equal ones) is given up. */
    private void keep(Sound s) {
        s.kept = true;
        keptClips.add(s);
        ready.add(s.clip);
        if (keptClips.size() <= MAX_CLIPS) return;
        Sound quiet = null;
        for (Sound k : keptClips) if (quiet == null || k.clip.peak < quiet.clip.peak - 1e-9 || (Math.abs(k.clip.peak - quiet.clip.peak) <= 1e-9 && k.start > quiet.start)) quiet = k;
        drop(quiet);
        if (quiet.run != null && quiet.run.best == quiet) quiet.run.best = null;
    }

    private void drop(Sound s) {
        if (!s.kept) return;
        s.kept = false;
        keptClips.remove(s);
        if (!ready.remove(s.clip)) dropped.add(s.clip);
    }

    private void addToFloor(double db) {
        int bin = (int) Math.floor((db - MIN_DB) * 2);
        bin = Math.max(0, Math.min(BINS - 1, bin));
        if (windowCount == WINDOW) {
            hist[window[windowPos]]--;
        } else {
            windowCount++;
        }
        window[windowPos] = bin;
        windowPos = (windowPos + 1) % WINDOW;
        hist[bin]++;
        // the lowest step that holds the percentile (need is at least 1 and never more than the frames in the window)
        int need = (int) Math.ceil(FLOOR_PERCENTILE * windowCount);
        int b = -1, sum = 0;
        while (sum < need) sum += hist[++b];
        floor = MIN_DB + b / 2.0 + 0.25;
    }

    // ------------------------------------------------------------------------------------- results

    /** The noise floor now (dB of full scale), NaN before the first frame with sound. */
    public double floor() {
        return floor;
    }

    /** Every mark decided so far, by start. */
    public List<Mark> marks() {
        List<Mark> out = new ArrayList<Mark>(marks);
        Collections.sort(out, BY_START);
        return out;
    }

    /** The marks decided since the last call, by start. */
    public List<Mark> takeMarks() {
        List<Mark> out = new ArrayList<Mark>(newMarks);
        newMarks.clear();
        Collections.sort(out, BY_START);
        return out;
    }

    /** The snoring stretches decided so far. */
    public List<Mark> stretches() {
        List<Mark> out = new ArrayList<Mark>();
        for (Mark m : marks()) if (SNORE.equals(m.kind)) out.add(m);
        return out;
    }

    /** The gaps of digital silence found so far. */
    public List<Gap> gaps() {
        return new ArrayList<Gap>(gaps);
    }

    /** The clips kept now, by start. */
    public List<Clip> clips() {
        List<Clip> out = new ArrayList<Clip>();
        for (Sound s : keptClips) out.add(s.clip);
        Collections.sort(out, new Comparator<Clip>() {
            @Override
            public int compare(Clip a, Clip b) {
                return Double.compare(a.start, b.start);
            }
        });
        return out;
    }

    /** Clips whose window has passed since the last call and that are kept: save their audio now. */
    public List<Clip> takeReady() {
        List<Clip> out = new ArrayList<Clip>(ready);
        ready.clear();
        return out;
    }

    /** Clips handed out by {@link #takeReady} and given up since (louder ones came): delete their audio. */
    public List<Clip> takeDropped() {
        List<Clip> out = new ArrayList<Clip>(dropped);
        dropped.clear();
        return out;
    }

    private static final Comparator<Mark> BY_START = new Comparator<Mark>() {
        @Override
        public int compare(Mark a, Mark b) {
            return Double.compare(a.start, b.start);
        }
    };
}
