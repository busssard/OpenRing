package app.openring.data;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.openring.core.SleepSounds;
import app.openring.core.analysis.AudioRing;
import app.openring.core.analysis.SoundDetector;

/**
 * One night of listening (issue #380, phase 1b): the part of the listening service that needs no Android, so the pipeline tests
 * run it. The service ({@code sync/SoundService}) only reads the microphone and hands every piece of samples to {@link #feed}.
 *
 * <ul>
 * <li>The 1a detector finds the sounds, with the start threshold of the microphone test ({@code Prefs.soundThreshold}).</li>
 * <li>The last {@link #RING_S} seconds of audio are kept ({@link AudioRing}); a clip the detector hands out is saved from them
 * as one file in the real clip folder, named after its sound's start ({@code s<ms>.m4a}). When the encoder fails, that clip
 * and the rest of the night are written as WAV, so no clip is lost. A clip given up later (louder ones came) loses its file.</li>
 * <li>Every mark is written as soon as it is decided, and written again when its clip changes (a snoring stretch keeps its
 * loudest snore's clip): with its file, or without sound ({@code gone} 1). Gaps (digital silence) are written as found.</li>
 * <li>Everything goes into the real history through {@code Db.beginWrite}, also while demo mode is shown (as the phone's
 * steps); while an analysis runs or a restore swaps the file, the rows wait in memory and are written with the next piece.</li>
 * <li>The night's row is written at the start and again each minute (its {@code stop} is the last time it was known to listen,
 * its {@code ended} empty while it runs), so a night the phone ended without a word is closed as {@code system} next time.</li>
 * <li>Dropped audio (review of #420): when the samples fall behind the clock by more than {@link #DRIFT_S}, the detector is
 * finished and started again at the clock's time, and the missing time is a gap ({@code system}). Marks never move.</li>
 * <li>One lock ({@link #LOCK}) guards every write of the night. {@link #stopRunning} (Delete all, "Delete all clips of this
 * night") takes it and closes the night, so nothing is written after it returns, not a row and not a file.</li>
 * </ul>
 */
public final class SoundNight {
    /** Writes one clip's samples (16 kHz mono) as a file. */
    public interface Writer {
        /** The file name's ending, ".m4a" or ".wav". */
        String ext();

        void write(File f, short[] pcm, int off, int len) throws Exception;
    }

    /** The fallback: a plain WAV file ({@code core/SleepSounds.wav}). */
    public static final Writer WAV = new Writer() {
        @Override
        public String ext() {
            return ".wav";
        }

        @Override
        public void write(File f, short[] pcm, int off, int len) throws Exception {
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(SleepSounds.wav(pcm, off, len, SoundDetector.RATE));
            } finally {
                o.close();
            }
        }
    };

    /** Seconds the samples may fall behind the clock before the detector starts again; seconds between the night row's updates. */
    public static final double DRIFT_S = 5, BEAT_S = 60;
    /** Seconds of audio kept: the longest clip and a few seconds for the pieces. */
    static final int RING_S = 24;

    /** Guards every write of a night and the change of {@link #running}. */
    static final Object LOCK = new Object();
    /** The night listening now; read without the lock (the screens must never wait for a write). */
    private static volatile SoundNight running;
    /** The marks of the running night so far, for its card. */
    private volatile int sounds;

    private final Context c;
    private final File folder;
    private final double threshold;
    private final Writer writer;
    /** The night's start (unix seconds), its row's key. */
    public final double start;
    private SoundDetector det;
    private double origin;
    private long fed;
    private final AudioRing ring = new AudioRing(RING_S * SoundDetector.RATE);
    /** Saved clip files by their sound's start ({@code Clip.sound}). */
    private final Map<Double, String> saved = new HashMap<Double, String>();
    /** Marks as their rows hold them now (start: file, "" without sound), and as they are to be written. */
    private final Map<Double, String> stored = new HashMap<Double, String>();
    private final Map<Double, Object[]> pendingMarks = new LinkedHashMap<Double, Object[]>();
    private final List<Object[]> pendingGaps = new ArrayList<Object[]>();
    private int gapsTaken;
    private boolean staleDone, nightDirty = true, wavOnly;
    private volatile boolean closed;
    private double heard, lastBeat = Double.NEGATIVE_INFINITY;
    private String ended;
    /** Clips the encoder could not write (each then went to WAV), and clips not saved at all; for the log. */
    int encoderFailures, saveFailures, writeFailures;

    private SoundNight(Context c, double start, double threshold, Writer writer) {
        this.c = c.getApplicationContext();
        this.folder = Sounds.folder(this.c, false);
        this.threshold = threshold;
        this.writer = writer == null ? WAV : writer;
        this.start = start;
        origin = start;
        heard = start;
        det = new SoundDetector(start, threshold);
    }

    /**
     * Starts a night at {@code now} (unix seconds, the time of the first sample) with the start {@code threshold} (dB above the
     * floor) and the clip {@code writer} (null: WAV). Writes its row (and closes nights a killed service left open). Throws
     * IllegalStateException when a night is listening already.
     */
    public static SoundNight start(Context c, double now, double threshold, Writer writer) {
        SoundNight n;
        synchronized (LOCK) {
            if (running != null) throw new IllegalStateException("a night is listening already");
            n = new SoundNight(c, now, threshold, writer);
            if (!n.folder.isDirectory()) n.folder.mkdirs();
            running = n;
            n.write(false);
        }
        return n;
    }

    /** Whether a night is listening now. */
    public static boolean isRunning() {
        return running != null;
    }

    /** The start of the night listening now (unix seconds), NaN for none. */
    public static double runningStart() {
        SoundNight n = running;
        return n == null ? Double.NaN : n.start;
    }

    /** The marks the night listening now has decided so far (0 for none). */
    public static int runningSounds() {
        SoundNight n = running;
        return n == null ? 0 : n.sounds;
    }

    /**
     * Closes the night listening now without writing anything more (Delete all, issue #380 Design review): once this returns,
     * no row and no file of it is written. Its {@link #feed} returns false from then on, which stops the service. Returns whether
     * one was listening.
     */
    public static boolean stopRunning() {
        synchronized (LOCK) {
            if (running == null) return false;
            running.closed = true;
            running = null;
            return true;
        }
    }

    /**
     * "Delete all clips of this night": stops the night first when it is the one listening (under the same lock, so nothing of
     * it is written afterwards), then deletes its rows and files ({@link Sounds#deleteNight}). Returns the marks deleted.
     */
    public static int deleteNight(Context c, Db db, double start) {
        synchronized (LOCK) {
            if (running != null && running.start == start && !Db.DEMO.equals(db.fileName)) {
                running.closed = true;
                running = null;
            }
            return Sounds.deleteNight(db, Sounds.folder(c, db), start);
        }
    }

    /**
     * The usual bedtime and wake up from the person's own nights (the real history, also in demo mode), for the bedtime
     * notification and the night's stop time; without them (a restore swaps the file now) the defaults.
     */
    public static SleepSounds.Usual usual(Context c, java.time.LocalDate today, java.time.ZoneId zone) {
        Db db = Db.beginWrite(c);
        if (db == null) return SleepSounds.usual(new ArrayList<double[]>(), zone);
        try {
            return SleepSounds.usual(Sounds.usualNights(db, today), zone);
        } finally {
            Db.endWrite();
        }
    }

    /** The retention and the sweep on the real history and its clip folder ({@link Sounds#tidy}); nothing while a restore swaps. */
    public static int tidyReal(Context c, int keepDays) throws java.io.IOException {
        Db db = Db.beginWrite(c);
        if (db == null) return 0;
        try {
            return Sounds.tidy(c, db, keepDays);
        } finally {
            Db.endWrite();
        }
    }

    /** Whether this night was closed (stopped, finished, or by Delete all). */
    public boolean closed() {
        return closed;
    }

    /**
     * Feeds {@code len} samples of {@code s} from {@code off}; {@code now} is the clock (unix seconds) when the piece's last sample
     * came. Saves the clips that are ready, writes the marks and gaps. Returns false once the night is closed (nothing done).
     */
    public boolean feed(short[] s, int off, int len, double now) {
        synchronized (LOCK) {
            if (closed) return false;
            double pieceStart = now - len / (double) SoundDetector.RATE;
            if (fed > 0 && pieceStart - (origin + fed / (double) SoundDetector.RATE) > DRIFT_S) restart(pieceStart);
            ring.add(s, off, len);
            det.feed(s, off, len);
            fed += len;
            heard = origin + fed / (double) SoundDetector.RATE;
            collect();
            if (heard - lastBeat >= BEAT_S) nightDirty = true;
            write(false);
            return true;
        }
    }

    /**
     * The end of the night ({@code why}: {@code Sounds.ENDED_HAND}, {@code ENDED_TIME}, {@code ENDED_LIMIT} or
     * {@code ENDED_SYSTEM}): the detector decides what is left, the last clips are saved and everything is written (waiting for a
     * running analysis). Nothing when it was closed already.
     */
    public void finish(String why) {
        synchronized (LOCK) {
            if (closed) return;
            det.finish();
            collect();
            ended = why;
            nightDirty = true;
            write(true);
            closed = true;
            if (running == this) running = null;
        }
    }

    /** The audio fell behind the clock: the detector so far is finished and a new one starts at {@code at}; the time between is a gap. */
    private void restart(double at) {
        det.finish();
        collect();
        pendingGaps.add(new Object[] {heard, at, Sounds.GAP_SYSTEM});
        det = new SoundDetector(at, threshold);
        origin = at;
        fed = 0;
        gapsTaken = 0;
        ring.clear();
        heard = at;
    }

    /** Saves the clips that are ready, deletes the ones given up, and notes the marks whose rows change and the new gaps. */
    private void collect() {
        boolean changed = false;
        for (SoundDetector.Clip k : det.takeReady()) {
            save(k);
            changed = true;
        }
        for (SoundDetector.Clip k : det.takeDropped()) {
            String f = saved.remove(k.sound);
            if (f != null) new File(folder, f).delete();
            changed = true;
        }
        if (!det.takeMarks().isEmpty()) changed = true;
        if (changed) {
            for (SoundDetector.Mark m : det.marks()) {
                SoundDetector.Clip k = m.clip();
                String f = k == null ? null : saved.get(k.sound);
                String want = f == null ? "" : f;
                Object[] pending = pendingMarks.get(m.start);
                String now = pending != null ? (String) pending[3] : stored.get(m.start);
                if (!want.equals(now)) pendingMarks.put(m.start, new Object[] {m.stop, m.kind, m.peak, want});
            }
        }
        if (changed) {
            int n = stored.size();
            for (Double k : pendingMarks.keySet()) if (!stored.containsKey(k)) n++;
            sounds = n;
        }
        List<SoundDetector.Gap> g = det.gaps();
        for (int i = gapsTaken; i < g.size(); i++) pendingGaps.add(new Object[] {g.get(i).start, g.get(i).stop, Sounds.GAP_SILENCE});
        gapsTaken = g.size();
    }

    /** Saves the audio of {@code k} from the ring: with the encoder, or as WAV when it fails (then for the rest of the night). */
    private void save(SoundDetector.Clip k) {
        long from = Math.round((k.start - origin) * SoundDetector.RATE), to = Math.round((k.stop - origin) * SoundDetector.RATE);
        short[] pcm = ring.copy(from, to);
        if (pcm.length == 0) return;
        String base = "s" + Math.round(k.sound * 1000);
        if (!wavOnly && writer != WAV) {
            File f = new File(folder, base + writer.ext());
            try {
                writer.write(f, pcm, 0, pcm.length);
                saved.put(k.sound, f.getName());
                return;
            } catch (Throwable e) {
                f.delete();
                wavOnly = true;
                encoderFailures++;
            }
        }
        File f = new File(folder, base + WAV.ext());
        try {
            WAV.write(f, pcm, 0, pcm.length);
            saved.put(k.sound, f.getName());
        } catch (Throwable e) {
            // the mark is stored without sound
            f.delete();
            saveFailures++;
        }
    }

    /**
     * Writes what is waiting into the real history. {@code force}: also while an analysis runs (the end of the night waits for
     * it); without it the rows wait for the next piece. While a restore swaps the file nothing is written, also with force.
     */
    private void write(boolean force) {
        if (pendingMarks.isEmpty() && pendingGaps.isEmpty() && !nightDirty && staleDone) return;
        if (!force && Analyzer.running()) return;
        final Db db = Db.beginWrite(c);
        if (db == null) return;
        try {
            final int clips = clipsAfter();
            db.inTransaction(new Runnable() {
                @Override
                public void run() {
                    if (!staleDone) Sounds.closeStale(db, start);
                    for (Map.Entry<Double, Object[]> e : pendingMarks.entrySet()) {
                        Object[] r = e.getValue();
                        String file = (String) r[3];
                        if (file.isEmpty()) Sounds.addMark(db, e.getKey(), (Double) r[0], (String) r[1], (Double) r[2]);
                        else Sounds.addClip(db, e.getKey(), (Double) r[0], (String) r[1], (Double) r[2], file);
                    }
                    for (Object[] g : pendingGaps) Sounds.addGap(db, (Double) g[0], (Double) g[1], (String) g[2]);
                    double floor = det.floor();
                    Sounds.addNight(db, start, heard, Double.isNaN(floor) ? null : floor, clips, ended);
                }
            });
            staleDone = true;
            for (Map.Entry<Double, Object[]> e : pendingMarks.entrySet()) stored.put(e.getKey(), (String) e.getValue()[3]);
            pendingMarks.clear();
            pendingGaps.clear();
            nightDirty = false;
            lastBeat = heard;
        } catch (RuntimeException e) {
            // tried again with the next piece; the rows stay waiting in memory
            writeFailures++;
        } finally {
            Db.endWrite();
        }
    }

    /** The marks with sound once the waiting rows are written. */
    private int clipsAfter() {
        Map<Double, String> all = new HashMap<Double, String>(stored);
        for (Map.Entry<Double, Object[]> e : pendingMarks.entrySet()) all.put(e.getKey(), (String) e.getValue()[3]);
        int n = 0;
        for (String f : all.values()) if (!f.isEmpty()) n++;
        return n;
    }
}
