import app.openring.core.analysis.SoundDetector;
import app.openring.core.analysis.SoundDetector.Clip;
import app.openring.core.analysis.SoundDetector.Gap;
import app.openring.core.analysis.SoundDetector.Mark;

import java.util.*;

/**
 * Sleep sounds (issue #380, phase 1a, {@code core/analysis/SoundDetector}) on made up audio, every sound made in code from a
 * seeded random generator (no recording): a quiet room and a steady fan find nothing; 14 known sounds are found within 0.1 s
 * with their kinds; snoring is grouped into one stretch; a cough and a talk like sound of the same length are told apart by
 * their zero crossings; the 60 clip limit keeps the loudest; the 5 s before a sound also at the very start of the night;
 * digital silence gives a gap; the same audio in other piece sizes gives the same result. Run with tests/run_tests.sh.
 */
public class SoundTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    static void check(String name, boolean ok, Object info) {
        if (!ok) System.out.println("  info: " + info);
        check(name, ok);
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = Objects.equals(got, want);
        if (!ok) System.out.println("  got <" + got + "> want <" + want + ">");
        check(name, ok);
    }

    static final int R = SoundDetector.RATE;
    static final double T0 = 1_790_000_000;

    public static void main(String[] args) {
        quiet();
        fan();
        known14();
        snoring();
        coughOrTalk();
        clipLimit();
        stretchClips();
        equalPeaks();
        clipWindows();
        gaps();
        pieces();
        System.out.println("sound: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------------------------- made up audio

    /** {@code seconds} of a quiet room: Gaussian noise of standard deviation {@code level} (30 is about -61 dB of full scale). */
    static double[] room(double seconds, double level, Random r) {
        double[] a = new double[(int) Math.round(seconds * R)];
        for (int i = 0; i < a.length; i++) a[i] = r.nextGaussian() * level;
        return a;
    }

    /** An envelope: {@code rise} s up, flat, {@code fall} s down, over {@code length} s. */
    static double env(double t, double length, double rise, double fall) {
        if (t < 0 || t > length) return 0;
        if (t < rise) return t / rise;
        if (t > length - fall) return (length - t) / fall;
        return 1;
    }

    /** Talking like: harmonics of a voice around 150 Hz, syllables at about 4 per second, never silent inside. */
    static void talk(double[] a, double at, double length, double amp, Random r) {
        double f0 = 120 + r.nextDouble() * 60, ph = r.nextDouble() * 6;
        int from = (int) Math.round(at * R), n = (int) Math.round(length * R);
        for (int i = 0; i < n && from + i < a.length; i++) {
            double t = i / (double) R;
            double syl = 0.65 + 0.35 * Math.sin(2 * Math.PI * 4 * t + ph);
            double v = Math.sin(2 * Math.PI * f0 * t) + 0.5 * Math.sin(2 * Math.PI * 2 * f0 * t) + 0.3 * Math.sin(2 * Math.PI * 3 * f0 * t);
            a[from + i] += amp * env(t, length, 0.03, 0.03) * syl * v / 1.8;
        }
    }

    /** Cough like: a burst of broadband noise, at full strength at once, fading, cut off at {@code length}. */
    static void cough(double[] a, double at, double length, double amp, Random r) {
        int from = (int) Math.round(at * R), n = (int) Math.round(length * R);
        for (int i = 0; i < n && from + i < a.length; i++) {
            double t = i / (double) R;
            double e = Math.min(1, t / 0.01) * Math.exp(-t / 0.4) * env(t, length, 0.001, 0.02);
            a[from + i] += amp * e * r.nextGaussian() * 0.6;
        }
    }

    /** A low thud of the same shape as a cough (sharp start, short), but a 200 Hz tone: few zero crossings. */
    static void thud(double[] a, double at, double length, double amp) {
        int from = (int) Math.round(at * R), n = (int) Math.round(length * R);
        for (int i = 0; i < n && from + i < a.length; i++) {
            double t = i / (double) R;
            double e = Math.min(1, t / 0.01) * Math.exp(-t / 0.4) * env(t, length, 0.001, 0.02);
            a[from + i] += amp * e * Math.sin(2 * Math.PI * 200 * t);
        }
    }

    /** A snore: a rumble around 90 Hz with a slow rise and fall. */
    static void snore(double[] a, double at, double length, double amp, Random r) {
        int from = (int) Math.round(at * R), n = (int) Math.round(length * R);
        double f = 80 + r.nextDouble() * 20;
        for (int i = 0; i < n && from + i < a.length; i++) {
            double t = i / (double) R;
            double v = Math.sin(2 * Math.PI * f * t) + 0.4 * Math.sin(2 * Math.PI * 2 * f * t + 1) + 0.05 * r.nextGaussian();
            a[from + i] += amp * env(t, length, 0.25, 0.25) * v / 1.4;
        }
    }

    static short[] pcm(double[] a) {
        short[] s = new short[a.length];
        for (int i = 0; i < a.length; i++) s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(a[i])));
        return s;
    }

    static SoundDetector run(short[] s, int piece) {
        SoundDetector d = new SoundDetector(T0);
        for (int i = 0; i < s.length; i += piece) d.feed(s, i, Math.min(piece, s.length - i));
        d.finish();
        return d;
    }

    static SoundDetector run(double[] a) {
        return run(pcm(a), SoundDetector.FRAME);
    }

    // ------------------------------------------------------------------------------------- tests

    static void quiet() {
        SoundDetector d = run(room(600, 30, new Random(1)));
        check("a quiet room for 10 minutes: no sound, no clip, no gap", d.marks().isEmpty() && d.clips().isEmpty() && d.gaps().isEmpty(), d.marks());
        check("the floor of a quiet room is about -61 dB", Math.abs(d.floor() - -61) < 2, d.floor());
        SoundDetector none = new SoundDetector(T0);
        none.finish();
        check("no audio at all: nothing, and the floor is not known", none.marks().isEmpty() && none.gaps().isEmpty() && Double.isNaN(none.floor()));
    }

    static void fan() {
        Random r = new Random(2);
        double[] a = room(600, 300, r);
        for (int i = 0; i < a.length; i++) a[i] += 400 * Math.sin(2 * Math.PI * 100 * i / (double) R);
        SoundDetector d = run(a);
        check("a steady fan with a hum for 10 minutes: no sound", d.marks().isEmpty() && d.clips().isEmpty(), d.marks());
        // a fan switched on in a quiet room: one sound at the switch, then the floor follows within the minute and nothing more
        double[] b = room(300, 30, new Random(3));
        Random q = new Random(4);
        for (int i = 60 * R; i < b.length; i++) b[i] += q.nextGaussian() * 300;
        SoundDetector e = run(b);
        List<Mark> m = e.marks();
        check("a fan switched on: one long sound from its start, ended once the floor followed it, nothing after", m.size() == 1
                && Math.abs(m.get(0).start - (T0 + 60)) < 0.1 && m.get(0).stop - m.get(0).start > 20 && m.get(0).stop - m.get(0).start < 61, m);
    }

    /** 14 sounds (talking, coughs, thuds) at known times, each more than 7 s from the next, in a quiet room. */
    static void known14() {
        Random r = new Random(5);
        double[] a = room(260, 30, r);
        double[][] truth = new double[14][];
        String[] kinds = new String[14];
        double at = 8.37;
        for (int i = 0; i < 14; i++) {
            int kind = i % 3;
            double len = kind == 0 ? 1.5 + r.nextDouble() * 4 : kind == 1 ? 0.35 + r.nextDouble() * 0.3 : 0.4 + r.nextDouble() * 0.3;
            double amp = 2000 + r.nextDouble() * 6000;
            if (kind == 0) talk(a, at, len, amp, r);
            else if (kind == 1) cough(a, at, len, amp, r);
            else thud(a, at, len, amp);
            truth[i] = new double[] {T0 + at, T0 + at + len};
            kinds[i] = kind == 1 ? SoundDetector.COUGH : SoundDetector.TALK;
            at += len + 8 + r.nextDouble() * 6;
        }
        SoundDetector d = run(a);
        List<Mark> m = d.marks();
        check("14 made up sounds: 14 found, nothing else", m.size() == 14, m);
        int within = 0, kindsRight = 0;
        for (int i = 0; i < Math.min(14, m.size()); i++) {
            if (Math.abs(m.get(i).start - truth[i][0]) <= 0.1 && Math.abs(m.get(i).stop - truth[i][1]) <= 0.1) within++;
            else System.out.println("  sound " + i + " " + m.get(i) + " truth " + truth[i][0] + "-" + truth[i][1]);
            if (m.get(i).kind.equals(kinds[i])) kindsRight++;
        }
        eq("each of the 14 starts and ends within 0.1 s of where it was put", within, 14);
        eq("each of the 14 has its kind (cough like, or talking or other sound)", kindsRight, 14);
        check("no snoring among sounds 8 s or more apart", d.stretches().isEmpty());
        boolean peaks = true;
        for (Mark x : m) peaks &= x.peak < 0 && x.peak > -40 && x.sounds == 1;
        check("each sound's peak is in dB of full scale, louder than the room", peaks, m);
        eq("14 clips, one for each sound", d.clips().size(), 14);
    }

    static void snoring() {
        Random r = new Random(6);
        double[] a = room(200, 30, r);
        talk(a, 10, 2, 4000, r);
        double at = 30;
        double first = T0 + at, last = 0;
        for (int i = 0; i < 12; i++) {
            double len = 1.4 + r.nextDouble() * 0.4;
            snore(a, at, len, 3000 + r.nextDouble() * 800, r);
            last = T0 + at + len;
            at += 3.8 + r.nextDouble() * 0.8;
        }
        talk(a, at + 15, 3, 4000, r);
        SoundDetector d = run(a);
        List<Mark> s = d.stretches();
        check("12 snores about 4 s apart: one snoring stretch of 12 sounds", s.size() == 1 && s.get(0).sounds == 12, d.marks());
        if (s.size() == 1) {
            check("the stretch runs from the first snore to the end of the last (within 0.3 s)", Math.abs(s.get(0).start - first) < 0.3
                    && Math.abs(s.get(0).stop - last) < 0.3, s.get(0));
            check("the stretch's minutes", Math.abs(s.get(0).minutes() - (last - first) / 60) < 0.01, s.get(0).minutes());
            check("the stretch keeps one clip, of its loudest snore", s.get(0).clip() != null && Math.abs(s.get(0).clip().peak - s.get(0).peak) < 1e-9, s.get(0).clip());
        }
        check("talking before and after stays apart: 3 marks, 3 clips", d.marks().size() == 3 && d.clips().size() == 3, d.marks());
        // two sounds in that rhythm are not snoring yet
        double[] b = room(60, 30, new Random(7));
        snore(b, 10, 1.5, 3000, r);
        snore(b, 14, 1.5, 3000, r);
        SoundDetector two = run(b);
        check("two snore like sounds only: two marks of talking or other sound", two.stretches().isEmpty() && two.marks().size() == 2
                && two.marks().get(0).kind.equals(SoundDetector.TALK), two.marks());
        // the rhythm broken: a sound 10 s later, one much louder, one much longer
        double[] c = room(120, 30, new Random(8));
        snore(c, 10, 1.5, 3000, r);
        snore(c, 14, 1.5, 3000, r);
        snore(c, 24, 1.5, 3000, r); // 10 s after the one before
        snore(c, 50, 1.5, 3000, r);
        snore(c, 54, 1.5, 9000, r); // about 9.5 dB louder
        snore(c, 58, 1.5, 3000, r);
        snore(c, 80, 1.5, 3000, r);
        snore(c, 84, 3.4, 3000, r); // more than twice as long
        snore(c, 90, 1.5, 3000, r);
        snore(c, 100, 0.4, 3000, r);
        snore(c, 101.7, 0.4, 3000, r); // 1.7 s after the one before: too fast
        snore(c, 103.4, 0.4, 3000, r);
        SoundDetector broken = run(c);
        check("the rhythm broken by a long pause, a much louder or a much longer sound: no stretch", broken.stretches().isEmpty()
                && broken.marks().size() == 12, broken.marks());
    }

    static void coughOrTalk() {
        Random r = new Random(9);
        double[] a = room(60, 30, r);
        cough(a, 10, 0.5, 6000, r);
        thud(a, 30, 0.5, 6000);
        SoundDetector d = run(a);
        List<Mark> m = d.marks();
        check("the same short, sharp shape: noise like is a cough, a low tone is talking or other sound", m.size() == 2
                && m.get(0).kind.equals(SoundDetector.COUGH) && m.get(1).kind.equals(SoundDetector.TALK), m);
        // noise like but long (2 s): not a cough
        double[] b = room(60, 30, r);
        cough(b, 10, 2.0, 6000, r);
        for (int i = 10 * R; i < 12 * R; i++) b[i] += r.nextGaussian() * 3000;
        SoundDetector e = run(b);
        check("noise like but 2 s long: talking or other sound", e.marks().size() == 1 && e.marks().get(0).kind.equals(SoundDetector.TALK), e.marks());
        // noise like and short, but growing to its loudest late (no sharp start): not a cough
        double[] g = room(60, 30, r);
        for (int i = 0; i < (int) (0.9 * R); i++) g[10 * R + i] += r.nextGaussian() * 6000 * Math.min(1, i / (0.6 * R)) * env(i / (double) R, 0.9, 0.001, 0.02);
        SoundDetector h = run(g);
        check("noise like and short, loudest only after 0.6 s: talking or other sound", h.marks().size() == 1 && h.marks().get(0).kind.equals(SoundDetector.TALK), h.marks());
    }

    static void clipLimit() {
        Random r = new Random(10);
        int n = 80;
        double[] a = room(12 * n + 20, 30, r);
        double[] amps = new double[n];
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            amps[i] = 800 + i * 150; // all different
            order.add(i);
        }
        Collections.shuffle(order, new Random(11));
        double[] starts = new double[n];
        for (int k = 0; k < n; k++) {
            int i = order.get(k);
            starts[i] = 10 + 12 * k;
            talk(a, starts[i], 1.5, amps[i], r);
        }
        short[] s = pcm(a);
        SoundDetector d = new SoundDetector(T0);
        Set<Double> saved = new HashSet<Double>();
        boolean neverBoth = true;
        for (int i = 0; i < s.length; i += 4000) {
            d.feed(s, i, Math.min(4000, s.length - i));
            for (Clip c : d.takeReady()) neverBoth &= saved.add(c.sound);
            for (Clip c : d.takeDropped()) neverBoth &= saved.remove(c.sound);
        }
        d.finish();
        for (Clip c : d.takeReady()) saved.add(c.sound);
        for (Clip c : d.takeDropped()) saved.remove(c.sound);
        Set<Double> loudest = new HashSet<Double>();
        for (int i = n - SoundDetector.MAX_CLIPS; i < n; i++) loudest.add(T0 + starts[i]);
        Set<Double> kept = new HashSet<Double>();
        for (Clip c : d.clips()) kept.add(c.sound);
        check("80 sounds: 80 marks, 60 clips kept", d.marks().size() == n && d.clips().size() == SoundDetector.MAX_CLIPS, d.marks().size() + " " + d.clips().size());
        // the starts are found to the frame, so compare by the nearest put sound
        Set<Double> keptNear = new HashSet<Double>();
        for (double k : kept) for (double l : loudest) if (Math.abs(k - l) < 0.1) keptNear.add(l);
        eq("the 60 kept are the 60 loudest", keptNear.size(), SoundDetector.MAX_CLIPS);
        check("what the caller saved minus what it was told to delete is exactly the kept clips, never saved twice", neverBoth && saved.equals(kept), saved.size());
        int withClip = 0;
        for (Mark m : d.marks()) if (m.clip() != null) withClip++;
        eq("60 marks have a clip, 20 keep only their mark", withClip, SoundDetector.MAX_CLIPS);
    }

    /** A stretch keeps its loudest snore's clip, also when a later one is louder; the clip limit can take it. */
    static void stretchClips() {
        Random r = new Random(17);
        double[] a = room(80, 30, r);
        double amp = 2000;
        for (int i = 0; i < 8; i++) {
            snore(a, 10 + 4 * i, 1.5, amp, r);
            amp *= 1.25; // about 2 dB louder each time
        }
        short[] s = pcm(a);
        SoundDetector d = new SoundDetector(T0);
        Set<Double> saved = new HashSet<Double>();
        int droppedAfterReady = 0;
        for (int i = 0; i < s.length; i += 512) {
            d.feed(s, i, 512);
            for (Clip c : d.takeReady()) saved.add(c.sound);
            for (Clip c : d.takeDropped()) {
                saved.remove(c.sound);
                droppedAfterReady++;
            }
        }
        d.finish();
        for (Clip c : d.takeReady()) saved.add(c.sound);
        for (Clip c : d.takeDropped()) saved.remove(c.sound);
        List<Mark> st = d.stretches();
        check("8 snores, each louder: one stretch whose clip is its last (loudest) snore's", st.size() == 1 && st.get(0).sounds == 8 && st.get(0).clip() != null
                && Math.abs(st.get(0).clip().sound - (T0 + 38)) < 0.1, st);
        check("the louder ones replaced the stretch's clip, the replaced ones handed back for deleting", droppedAfterReady >= 2 && saved.size() == 1
                && d.clips().size() == 1, droppedAfterReady + " " + saved);
        // a quiet stretch after 60 louder sounds: the limit takes its clips, the mark stays without one
        double[] b = room(12 * 60 + 80, 30, r);
        for (int i = 0; i < 60; i++) talk(b, 10 + 12 * i, 1.5, 6000, r);
        for (int i = 0; i < 6; i++) snore(b, 12 * 60 + 20 + 4 * i, 1.5, 800, r);
        SoundDetector e = run(b);
        List<Mark> qs = e.stretches();
        check("a quiet stretch when 60 louder clips are kept: the stretch is marked, without a clip; the 60 stay", qs.size() == 1 && qs.get(0).clip() == null
                && e.clips().size() == 60 && e.marks().size() == 61, qs);
    }

    /** Clips of exactly the same loudness at the limit: the newest is the one given up. */
    static void equalPeaks() {
        int n = 61, step = 375 * SoundDetector.FRAME; // 12 s, whole frames, so every sound is the same samples
        short[] s = new short[step * (n + 1)];
        for (int i = 0; i < s.length; i++) s[i] = (short) Math.round(30 * Math.sin(2 * Math.PI * i / 128.0));
        for (int k = 0; k < n; k++) {
            int from = step * (k + 1) - 100 * SoundDetector.FRAME;
            for (int i = 0; i < 48 * SoundDetector.FRAME; i++) s[from + i] = (short) Math.round(4000 * Math.sin(2 * Math.PI * i / 64.0));
        }
        SoundDetector d = run(s, 4096);
        Set<Double> peaks = new HashSet<Double>();
        for (Mark m : d.marks()) peaks.add(m.peak);
        double newest = d.marks().get(n - 1).start;
        boolean newestKept = false;
        for (Clip c : d.clips()) newestKept |= c.sound == newest;
        check("61 sounds of exactly the same loudness: 60 clips kept, the newest given up", d.marks().size() == n && peaks.size() == 1
                && d.clips().size() == 60 && !newestKept, peaks + " " + d.marks().size());
    }

    static void clipWindows() {
        Random r = new Random(12);
        double[] a = room(120, 30, r);
        talk(a, 1.0, 2, 4000, r);   // 1 s after the start of the night
        talk(a, 30, 2, 4000, r);
        talk(a, 60, 30, 4000, r);   // longer than a clip
        SoundDetector d = run(a);
        List<Clip> c = d.clips();
        check("three clips", c.size() == 3, c);
        if (c.size() != 3) return;
        check("a sound 1 s into the night: its clip starts at the night's start (the 5 s before, as far as there is audio)",
                c.get(0).start == T0 && Math.abs(c.get(0).stop - (T0 + 3 + 3)) < 0.1, c.get(0));
        check("a sound at 30 s: its clip from 5 s before to 3 s after", Math.abs(c.get(1).start - (T0 + 25)) < 0.1 && Math.abs(c.get(1).stop - (T0 + 35)) < 0.1, c.get(1));
        check("a sound of 30 s: its clip is 20 s from 5 s before it", Math.abs(c.get(2).start - (T0 + 55)) < 0.1 && Math.abs(c.get(2).stop - c.get(2).start - 20) < 1e-6, c.get(2));
        check("the 30 s sound is one mark of 30 s, talking or other sound", Math.abs(d.marks().get(2).stop - d.marks().get(2).start - 30) < 0.1
                && d.marks().get(2).kind.equals(SoundDetector.TALK), d.marks().get(2));
        // a sound going on at the end of the night: it ends there, its clip too
        double[] b = room(40, 30, new Random(13));
        talk(b, 35, 10, 4000, r);
        SoundDetector e = run(b);
        check("a sound cut off by the end of the night: one mark and one clip ending at the last sample", e.marks().size() == 1 && e.clips().size() == 1
                && Math.abs(e.clips().get(0).stop - (T0 + 40)) < 0.04 && Math.abs(e.marks().get(0).stop - (T0 + 40)) < 0.04, e.clips());
        // a clip is ready only once its window has passed: the caller needs only the last 20 s of audio
        short[] s = pcm(room(60, 30, new Random(14)));
        double[] t = new double[s.length];
        for (int i = 0; i < s.length; i++) t[i] = s[i];
        talk(t, 20, 2, 4000, r);
        s = pcm(t);
        SoundDetector f = new SoundDetector(T0);
        double readyAt = -1;
        for (int i = 0; i < s.length; i += 512) {
            f.feed(s, i, 512);
            if (readyAt < 0 && !f.takeReady().isEmpty()) readyAt = (i + 512) / (double) R;
        }
        check("the clip is handed out right after its window (sound end plus 3 s), not before", readyAt >= 25 - 0.05 && readyAt <= 25.2, readyAt);
    }

    static void gaps() {
        Random r = new Random(15);
        double[] a = room(60, 30, r);
        for (int i = 10 * R; i < 13 * R; i++) a[i] = 0;          // 3 s of digital silence
        for (int i = 20 * R + 77; i < 21 * R + 77; i++) a[i] = 0; // 1 s: too short
        for (int i = 50 * R + 5; i < a.length; i++) a[i] = 0;    // to the end
        short[] s = pcm(a);
        // noise rounds to 0 now and then: only stretches set to 0 above are long enough
        SoundDetector d = run(s, 1000);
        List<Gap> g = d.gaps();
        check("two gaps of digital silence (3 s, and the last 10 s), not the 1 s one", g.size() == 2, g);
        if (g.size() == 2) {
            check("the 3 s gap to the sample", Math.abs(g.get(0).start - (T0 + 10)) < 2.0 / R && Math.abs(g.get(0).stop - (T0 + 13)) < 2.0 / R, g.get(0));
            check("the gap at the end lasts to the last sample", Math.abs(g.get(1).start - (T0 + 50 + 5.0 / R)) < 2.0 / R && g.get(1).stop == T0 + 60, g.get(1));
        }
        check("digital silence and the room after it make no sound (the floor leaves silence out)", d.marks().isEmpty(), d.marks());
        // only silence: one gap, no sound
        SoundDetector z = run(new short[R * 30], 512);
        check("30 s of digital silence: one gap of 30 s, no sound, no floor", z.gaps().size() == 1 && z.gaps().get(0).stop - z.gaps().get(0).start == 30
                && z.marks().isEmpty() && Double.isNaN(z.floor()), z.gaps());
        // a talk after the silence is found as usual
        double[] b = room(40, 30, r);
        for (int i = 5 * R; i < 15 * R; i++) b[i] = 0;
        talk(b, 25, 2, 4000, r);
        SoundDetector e = run(b);
        check("after a gap a sound is found as usual", e.marks().size() == 1 && Math.abs(e.marks().get(0).start - (T0 + 25)) < 0.1 && e.gaps().size() == 1, e.marks());
    }

    static void pieces() {
        Random r = new Random(16);
        double[] a = room(120, 30, r);
        talk(a, 10, 2, 4000, r);
        cough(a, 25, 0.5, 6000, r);
        for (int i = 0; i < 5; i++) snore(a, 40 + 4 * i, 1.5, 3000, r);
        for (int i = 70 * R; i < 74 * R; i++) a[i] = 0;
        short[] s = pcm(a);
        String one = describe(run(s, 512)), two = describe(run(s, 333)), three = describe(run(s, s.length));
        check("the same audio in pieces of 512, 333 or all at once: the same marks, clips and gaps", one.equals(two) && one.equals(three), one + "\n" + two);
        check("and the same again (deterministic)", one.equals(describe(run(s, 512))));
        check("the mix: talking, a cough, a stretch of 5 snores, a gap", one.contains("talk ") && one.contains("cough ") && one.contains("snore ") && one.contains(" x5")
                && one.contains("gap "), one);
        SoundDetector d = run(s, 512);
        d.feed(s);
        d.finish();
        check("nothing is taken after finish", describe(d).equals(one));
        SoundDetector t = new SoundDetector(T0);
        t.feed(s);
        List<Mark> first = t.takeMarks();
        t.finish();
        List<Mark> rest = t.takeMarks();
        check("takeMarks hands each mark out once", first.size() + rest.size() == t.marks().size() && t.takeMarks().isEmpty(), first.size() + " " + rest.size());
    }

    static String describe(SoundDetector d) {
        StringBuilder b = new StringBuilder();
        for (Mark m : d.marks()) b.append(m).append(" | ").append(m.clip()).append('\n');
        for (Gap g : d.gaps()) b.append(g).append('\n');
        return b.toString();
    }
}
