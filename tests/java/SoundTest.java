import app.openring.core.analysis.SoundDetector;
import app.openring.core.analysis.SoundDetector.Clip;
import app.openring.core.analysis.SoundDetector.Gap;
import app.openring.core.analysis.SoundDetector.Mark;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Sleep sounds (issue #380, phase 1a, {@code core/analysis/SoundDetector}) on made up audio, every sound made in code from a
 * seeded random generator (no recording): a quiet room and a steady fan find nothing; 14 known sounds are found within 0.1 s
 * with their kinds; snoring is grouped into one stretch; a cough and a talk like sound of the same length are told apart by
 * their zero crossings; the 60 clip limit keeps the loudest (and a night with a snoring stretch ends with exactly 60); each
 * clip belongs to the mark whose clip() it is; a lower start threshold finds a quieter sound; the 5 s before a sound also at
 * the very start of the night; digital silence gives a gap; the same audio in other piece sizes gives the same result. Run
 * with tests/run_tests.sh.
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
        stretchAtLimit();
        clipMarks();
        threshold();
        equalPeaks();
        clipWindows();
        gaps();
        pieces();
        // phase 1b: the microphone test (owner's wish of 21 November), bedtime, retention, texts, WAV, demo tones, the ring
        micThreshold();
        micLevels();
        thresholdFromTest();
        usualTimes();
        keepChoices();
        texts1b();
        wavFile();
        demoTones();
        audioRing();
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

    /**
     * A full night with a snoring stretch ends with exactly 60 clips (code review of ee03389 on #420): the first two snores of
     * a row are not yet a stretch, and their clips must not push out kept clips that the stretch then leaves empty; a louder
     * snore replacing the stretch's clip at exactly 60 gives up the old one, not an unrelated clip.
     */
    static void stretchAtLimit() {
        for (int talks : new int[] {59, 70}) {
            Random r = new Random(30 + talks);
            double[] a = room(12 * talks + 80, 30, r);
            for (int i = 0; i < talks; i++) talk(a, 10 + 12 * i, 1.5, 1500 + 60 * i, r);
            double amp = 3000;
            for (int i = 0; i < 8; i++) {
                snore(a, 12 * talks + 20 + 4 * i, 1.5, amp, r);
                amp *= 1.12; // about 1 dB louder each time, so the stretch's clip moves on
            }
            short[] s = pcm(a);
            SoundDetector d = new SoundDetector(T0);
            Set<Double> saved = new HashSet<Double>();
            boolean neverBoth = true;
            for (int i = 0; i < s.length; i += 512) {
                d.feed(s, i, Math.min(512, s.length - i));
                for (Clip c : d.takeReady()) neverBoth &= saved.add(c.sound);
                for (Clip c : d.takeDropped()) neverBoth &= saved.remove(c.sound);
            }
            d.finish();
            for (Clip c : d.takeReady()) neverBoth &= saved.add(c.sound);
            for (Clip c : d.takeDropped()) neverBoth &= saved.remove(c.sound);
            List<Mark> st = d.stretches();
            check(talks + " talking sounds and a stretch of 8 snores: one stretch, " + (talks + 1) + " marks", st.size() == 1 && st.get(0).sounds == 8
                    && d.marks().size() == talks + 1, d.marks().size() + " " + st);
            eq(talks + " talking sounds and a stretch of 8 snores: exactly 60 clips at the end of the night", d.clips().size(), SoundDetector.MAX_CLIPS);
            check(talks + " talking sounds and a stretch: the stretch keeps its loudest snore's clip", st.size() == 1 && st.get(0).clip() != null
                    && Math.abs(st.get(0).clip().peak - st.get(0).peak) < 1e-9, st);
            Set<Double> kept = new HashSet<Double>();
            for (Clip c : d.clips()) kept.add(c.sound);
            check(talks + " talking sounds and a stretch: saved minus deleted is exactly the kept clips", neverBoth && saved.equals(kept), saved.size() + " " + kept.size());
            int withClip = 0;
            for (Mark m : d.marks()) if (m.clip() != null) withClip++;
            eq(talks + " talking sounds and a stretch: 60 marks have a clip", withClip, SoundDetector.MAX_CLIPS);
        }
        // two loud sounds in the snoring rhythm after 60 quieter ones: the row stays two single sounds, and only then do their
        // clips push out the two quietest
        Random r = new Random(40);
        double[] b = room(12 * 60 + 60, 30, r);
        for (int i = 0; i < 60; i++) talk(b, 10 + 12 * i, 1.5, 1500 + 60 * i, r);
        snore(b, 12 * 60 + 20, 1.5, 9000, r);
        snore(b, 12 * 60 + 24, 1.5, 9000, r);
        SoundDetector e = new SoundDetector(T0);
        short[] s = pcm(b);
        Set<Double> saved = new HashSet<Double>();
        int most = 0;
        for (int i = 0; i < s.length; i += 512) {
            e.feed(s, i, Math.min(512, s.length - i));
            for (Clip c : e.takeReady()) saved.add(c.sound);
            for (Clip c : e.takeDropped()) saved.remove(c.sound);
            most = Math.max(most, e.clips().size());
        }
        e.finish();
        for (Clip c : e.takeReady()) saved.add(c.sound);
        for (Clip c : e.takeDropped()) saved.remove(c.sound);
        List<Mark> m = e.marks();
        boolean twoLoudKept = m.size() == 62 && m.get(60).clip() != null && m.get(61).clip() != null;
        boolean quietestGone = m.size() == 62 && m.get(0).clip() == null && m.get(1).clip() == null;
        check("60 sounds and then two louder ones in the snoring rhythm: 62 marks, 60 clips, the two louder kept, the two quietest given up",
                e.clips().size() == SoundDetector.MAX_CLIPS && twoLoudKept && quietestGone && e.stretches().isEmpty(), e.clips().size() + " " + m.size());
        check("while the row was undecided there were never more than 62 clips, and saved minus deleted is the kept clips", most <= SoundDetector.MAX_CLIPS + 2
                && saved.size() == SoundDetector.MAX_CLIPS, most + " " + saved.size());
    }

    /**
     * Which mark a clip belongs to (code review of ee03389 on #420): the mark whose clip() it is. Clip.sound lies between that
     * mark's start and stop; for a stretch it is the loudest snore's start, not the stretch's. A cough between two snores is
     * its own mark inside the stretch's start and stop, so time alone would not find the mark.
     */
    static void clipMarks() {
        Random r = new Random(41);
        double[] a = room(80, 30, r);
        double amp = 2500;
        for (int i = 0; i < 5; i++) {
            snore(a, 10 + 6 * i, 1.5, amp, r);
            amp *= 1.15; // the loudest is the last
        }
        cough(a, 13, 0.4, 6000, r); // between the snores at 10 and 16
        talk(a, 55, 2, 4000, r);
        SoundDetector d = run(a);
        List<Mark> m = d.marks();
        List<Mark> st = d.stretches();
        check("5 snores with a cough among them: one stretch of 5, the cough and the talking are their own marks", st.size() == 1 && st.get(0).sounds == 5
                && m.size() == 3 && m.get(1).kind.equals(SoundDetector.COUGH) && m.get(2).kind.equals(SoundDetector.TALK), m);
        if (st.size() != 1 || m.size() != 3) return;
        Mark stretch = st.get(0), coughMark = m.get(1);
        check("the cough lies inside the stretch's start and stop", coughMark.start > stretch.start && coughMark.stop < stretch.stop, coughMark + " " + stretch);
        Clip sc = stretch.clip();
        check("the stretch's clip is its loudest (last) snore's: clip.sound is that snore's start, inside the stretch, not its start", sc != null
                && Math.abs(sc.sound - (T0 + 34)) < 0.1 && sc.sound > stretch.start + 1 && sc.sound <= stretch.stop, sc + " " + stretch);
        boolean each = true;
        for (Clip c : d.clips()) {
            int owners = 0;
            for (Mark x : m) if (x.clip() == c) {
                owners++;
                each &= c.sound >= x.start - 1e-9 && c.sound <= x.stop + 1e-9;
            }
            each &= owners == 1;
        }
        check("each kept clip is the clip of exactly one mark, and its sound lies between that mark's start and stop", each && d.clips().size() == 3, d.clips());
        check("the cough's clip is the cough mark's, though the stretch's start and stop also hold its sound", coughMark.clip() != null
                && coughMark.clip().sound == coughMark.start && coughMark.clip() != sc, coughMark.clip());
    }

    /** The start threshold is a constructor parameter (owner's wish on #380): 10 dB by default, lower finds quieter sounds. */
    static void threshold() {
        Random r = new Random(42);
        double[] a = room(60, 30, r);
        // a steady 300 Hz tone about 8 dB above the room: (30² + A²/2) / 30² = 10^0.8 gives A about 98
        for (int i = 30 * R; i < 32 * R; i++) a[i] += 98 * Math.sin(2 * Math.PI * 300 * (i - 30 * R) / (double) R);
        short[] s = pcm(a);
        SoundDetector def = run(s, 512);
        SoundDetector low = new SoundDetector(T0, 6);
        low.feed(s);
        low.finish();
        eq("the default start threshold is 10 dB", def.threshold(), 10.0);
        check("a sound about 8 dB above the room: missed with the default 10 dB", def.marks().isEmpty(), def.marks());
        check("the same sound with a threshold of 6 dB: found, at its start, with a clip", low.marks().size() == 1 && Math.abs(low.marks().get(0).start - (T0 + 30)) < 0.1
                && Math.abs(low.marks().get(0).stop - (T0 + 32)) < 0.1 && low.clips().size() == 1, low.marks());
        SoundDetector ten = new SoundDetector(T0, SoundDetector.ABOVE_DB);
        ten.feed(s);
        ten.finish();
        check("a threshold of 10 given is the same as the default", describe(ten).equals(describe(def)));
        boolean refused = true;
        for (double bad : new double[] {0, -3, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                new SoundDetector(T0, bad);
                refused = false;
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
        check("a threshold of 0, below 0, not a number or infinite is refused", refused);
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

    // ------------------------------------------------------------------------------------- phase 1b

    /** The start threshold from a test's margin: half, kept between 6 and 15 dB; no sound keeps the default 10 dB. */
    static void micThreshold() {
        eq("a margin of 5 dB gives the lowest threshold, 6 dB", app.openring.core.SleepSounds.threshold(5), 6.0);
        eq("a margin of 12 dB gives 6 dB (half of it)", app.openring.core.SleepSounds.threshold(12), 6.0);
        eq("a margin of 16 dB gives 8 dB", app.openring.core.SleepSounds.threshold(16), 8.0);
        eq("a margin of 30 dB gives the highest threshold, 15 dB", app.openring.core.SleepSounds.threshold(30), 15.0);
        eq("a margin of 40 dB still gives 15 dB", app.openring.core.SleepSounds.threshold(40), 15.0);
        eq("no sound keeps the default 10 dB", app.openring.core.SleepSounds.threshold(Double.NaN), SoundDetector.ABOVE_DB);
        eq("an infinite margin keeps the default", app.openring.core.SleepSounds.threshold(Double.POSITIVE_INFINITY), SoundDetector.ABOVE_DB);
        eq("no sound is its own verdict", app.openring.core.SleepSounds.verdict(Double.NaN), app.openring.core.SleepSounds.NO_SOUND);
        eq("9.9 dB: too far", app.openring.core.SleepSounds.verdict(9.9), app.openring.core.SleepSounds.TOO_FAR);
        eq("a voice not louder than the room: too far", app.openring.core.SleepSounds.verdict(-3), app.openring.core.SleepSounds.TOO_FAR);
        eq("10 dB: works", app.openring.core.SleepSounds.verdict(10), app.openring.core.SleepSounds.WORKS);
        eq("19.9 dB: works", app.openring.core.SleepSounds.verdict(19.9), app.openring.core.SleepSounds.WORKS);
        eq("20 dB: good", app.openring.core.SleepSounds.verdict(20), app.openring.core.SleepSounds.GOOD);
        // review of #450: only a test that worked sets the threshold; too far or no sound keeps the one before
        check("Good and Works set the threshold", app.openring.core.SleepSounds.saves(app.openring.core.SleepSounds.GOOD)
                && app.openring.core.SleepSounds.saves(app.openring.core.SleepSounds.WORKS));
        check("too far (9.9 dB) and no sound keep the threshold before", !app.openring.core.SleepSounds.saves(app.openring.core.SleepSounds.verdict(9.9))
                && !app.openring.core.SleepSounds.saves(app.openring.core.SleepSounds.verdict(Double.NaN)));
        check("a test at 10 dB sets 6 dB, one at 9.9 dB changes nothing", app.openring.core.SleepSounds.saves(app.openring.core.SleepSounds.verdict(10))
                && app.openring.core.SleepSounds.threshold(10) == 6.0);
        eq("the good text (owner's wish)", app.openring.core.SleepSounds.verdictText(app.openring.core.SleepSounds.GOOD), "Good, quiet talking and snoring will be found.");
        eq("the works text", app.openring.core.SleepSounds.verdictText(app.openring.core.SleepSounds.WORKS),
                "Works, but quiet sounds may be missed. Put the phone closer to your head.");
        eq("the too far text", app.openring.core.SleepSounds.verdictText(app.openring.core.SleepSounds.TOO_FAR),
                "Too far or the room is too loud (a fan?). Move the phone closer and test again.");
        eq("the no sound text", app.openring.core.SleepSounds.verdictText(app.openring.core.SleepSounds.NO_SOUND), "The microphone gave no sound. Another app may be using it.");
        check("6 and 15 dB are valid stored thresholds, 5.9 and 15.1 are not", app.openring.core.SleepSounds.validThreshold(6) && app.openring.core.SleepSounds.validThreshold(15)
                && !app.openring.core.SleepSounds.validThreshold(5.9) && !app.openring.core.SleepSounds.validThreshold(15.1) && !app.openring.core.SleepSounds.validThreshold(Double.NaN));
        eq("the margin of a test without room sound is not a number", app.openring.core.SleepSounds.margin(Double.NaN, -40), Double.NaN);
    }

    /** The levels the test measures, in the detector's own units. */
    static void micLevels() {
        short[] zero = new short[SoundDetector.FRAME];
        eq("digital silence is the bottom of the scale", SoundDetector.level(zero, 0, zero.length), SoundDetector.MIN_DB);
        eq("no samples are the bottom of the scale", SoundDetector.level(zero, 0, 0), SoundDetector.MIN_DB);
        short[] full = new short[SoundDetector.FRAME];
        for (int i = 0; i < full.length; i++) full[i] = (short) (i % 2 == 0 ? 32767 : -32767);
        check("a full scale square wave is 0 dB", Math.abs(SoundDetector.level(full, 0, full.length)) < 0.01, SoundDetector.level(full, 0, full.length));
        Random r = new Random(3);
        short[] room = pcm(room(10, 30, r));
        double[] lv = app.openring.core.SleepSounds.frameLevels(room, room.length);
        eq("10 s are 312 whole frames", lv.length, 10 * R / SoundDetector.FRAME);
        double rl = app.openring.core.SleepSounds.roomLevel(lv);
        check("a room of noise at 30 (about -61 dB of full scale) measures about -61 dB", Math.abs(rl - (-61.0)) < 0.5, rl);
        double[] silent = app.openring.core.SleepSounds.frameLevels(new short[10 * R], 10 * R);
        eq("a silent microphone has no room level", app.openring.core.SleepSounds.roomLevel(silent), Double.NaN);
        eq("and no voice level", app.openring.core.SleepSounds.voiceLevel(silent), Double.NaN);
        eq("no frames have no room level", app.openring.core.SleepSounds.roomLevel(new double[0]), Double.NaN);
        // half the frames silent (another app took the microphone half way): left out of the room, as the detector's floor does
        double[] half = new double[40];
        for (int i = 0; i < half.length; i++) half[i] = i < 20 ? SoundDetector.MIN_DB : -50;
        eq("digital silence is left out of the room level", app.openring.core.SleepSounds.roomLevel(half), -50.0);
        // the loudest second: a short loud word in 8 s of quiet counts as the power mean of its second
        double[] voice = new double[250];
        Arrays.fill(voice, -60);
        for (int i = 100; i < 131; i++) voice[i] = -30;
        check("the loudest second of 31 loud frames is their level", Math.abs(app.openring.core.SleepSounds.voiceLevel(voice) - (-30)) < 1e-6,
                app.openring.core.SleepSounds.voiceLevel(voice));
        double[] blip = new double[250];
        Arrays.fill(blip, -60);
        blip[100] = -30;
        double bl = app.openring.core.SleepSounds.voiceLevel(blip);
        check("one loud frame counts as a thirty first of its second (a click is not a voice)", bl < -43 && bl > -46, bl);
        double[] shortTest = {-40, -40, -40};
        check("fewer frames than a second: their power mean", Math.abs(app.openring.core.SleepSounds.voiceLevel(shortTest) - (-40)) < 1e-9);
    }

    /**
     * The owner's case on #380: a made up test with the voice about 16 dB above the room sets the threshold to about 8 dB, which
     * finds a quiet sound about 8 dB above the room that the default 10 dB misses.
     */
    static void thresholdFromTest() {
        Random q = new Random(7);
        double[] room = room(app.openring.core.SleepSounds.ROOM_S, 30, q);
        double[] voice = room(app.openring.core.SleepSounds.VOICE_S, 30, q);
        // the sentence: 3 s of a 180 Hz tone, (30² + 264²/2) / 30² is about 10^1.6, so about 16 dB above the room
        for (int i = 2 * R + 1; i < 5 * R; i++) voice[i] += 264 * Math.sin(2 * Math.PI * 180 * i / (double) R);
        double rl = app.openring.core.SleepSounds.roomLevel(app.openring.core.SleepSounds.frameLevels(pcm(room), room.length));
        double vl = app.openring.core.SleepSounds.voiceLevel(app.openring.core.SleepSounds.frameLevels(pcm(voice), voice.length));
        double margin = app.openring.core.SleepSounds.margin(rl, vl);
        check("the made up test: the voice about 16 dB above the room", Math.abs(margin - 16) < 0.5, margin);
        eq("16 dB: works, quiet sounds may be missed", app.openring.core.SleepSounds.verdict(margin), app.openring.core.SleepSounds.WORKS);
        double th = app.openring.core.SleepSounds.threshold(margin);
        check("the threshold from it is about 8 dB", Math.abs(th - 8) < 0.25, th);
        Random r = new Random(42);
        double[] a = room(60, 30, r);
        // the same quiet sound as threshold(): a steady 300 Hz tone about 8 dB above the room
        for (int i = 30 * R; i < 32 * R; i++) a[i] += 98 * Math.sin(2 * Math.PI * 300 * (i - 30 * R) / (double) R);
        short[] s = pcm(a);
        SoundDetector def = run(s, 640);
        SoundDetector tested = new SoundDetector(T0, th);
        for (int i = 0; i < s.length; i += 640) tested.feed(s, i, Math.min(640, s.length - i));
        tested.finish();
        check("with the default 10 dB the quiet sound is missed", def.marks().isEmpty(), def.marks());
        check("with the threshold from the 16 dB test it is found, at its time, with a clip", tested.marks().size() == 1
                && Math.abs(tested.marks().get(0).start - (T0 + 30)) < 0.1 && tested.clips().size() == 1, tested.marks());
        eq("the detector uses the threshold it was given", tested.threshold(), th);
    }

    static double at(LocalDateTime t) {
        return t.atZone(ZURICH).toEpochSecond();
    }

    static final java.time.ZoneId ZURICH = java.time.ZoneId.of("Europe/Zurich");

    /** Nights at {@code bed} to {@code wake} (clock minutes), one a day from 1 November 2026. */
    static List<double[]> nights(int n, int bedMin, int wakeMin) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < n; i++) {
            java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 1).plusDays(i);
            LocalDateTime b = bedMin >= 18 * 60 ? day.minusDays(1).atTime(bedMin / 60, bedMin % 60) : day.atTime(bedMin / 60, bedMin % 60);
            out.add(new double[] {at(b), at(day.atTime(wakeMin / 60, wakeMin % 60))});
        }
        return out;
    }

    /** The usual bedtime (notification 30 min before it) and wake up (stop an hour after it), from the last 14 nights. */
    static void usualTimes() {
        app.openring.core.SleepSounds.Usual six = app.openring.core.SleepSounds.usual(nights(6, 23 * 60 + 10, 7 * 60 + 5), ZURICH);
        check("6 nights are not enough: the notification at 22:30, the stop at 9:00", !six.known() && six.remindMinute() == 22 * 60 + 30 && six.stopMinute() == 9 * 60, six.nights);
        eq("the line says why", app.openring.core.SleepSounds.remindLine(six), "Next at 22:30 (until 7 nights are recorded)");
        app.openring.core.SleepSounds.Usual seven = app.openring.core.SleepSounds.usual(nights(7, 23 * 60 + 10, 7 * 60 + 5), ZURICH);
        check("7 nights at 23:10 to 07:05: the notification at 22:40, the stop at 08:05", seven.known() && seven.remindMinute() == 22 * 60 + 40 && seven.stopMinute() == 8 * 60 + 5,
                seven.remindMinute() + " " + seven.stopMinute());
        eq("the line names the usual bedtime", app.openring.core.SleepSounds.remindLine(seven), "Next at 22:40 (30 min before your usual bedtime)");
        // the last 14 count: 10 old nights at 21:00 and 14 new ones at 23:10 give 23:10
        List<double[]> mixed = nights(10, 21 * 60, 6 * 60);
        for (double[] n : nights(14, 23 * 60 + 10, 7 * 60 + 5)) mixed.add(new double[] {n[0] + 20 * 86400, n[1] + 20 * 86400});
        app.openring.core.SleepSounds.Usual last = app.openring.core.SleepSounds.usual(mixed, ZURICH);
        check("only the last 14 nights count", last.remindMinute() == 22 * 60 + 40 && last.nights == 14, last.remindMinute() + " " + last.nights);
        // bedtimes around midnight: counted from 18:00, so 00:20 is later than 23:50 (the median is 00:20, the notification 23:50)
        List<double[]> late = new ArrayList<double[]>();
        int[] beds = {23 * 60 + 50, 10, 15, 20, 30, 40, 60};
        for (int i = 0; i < beds.length; i++) {
            java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 1).plusDays(i);
            LocalDateTime b = beds[i] >= 18 * 60 ? day.minusDays(1).atTime(beds[i] / 60, beds[i] % 60) : day.atTime(beds[i] / 60, beds[i] % 60);
            late.add(new double[] {at(b), at(day.atTime(8, 0))});
        }
        app.openring.core.SleepSounds.Usual mid = app.openring.core.SleepSounds.usual(late, ZURICH);
        check("bedtimes around midnight: usual 00:20, the notification at 23:50", Math.abs(mid.bed - 20) < 1e-9 && mid.remindMinute() == 23 * 60 + 50, mid.bed + " " + mid.remindMinute());
        // an early sleeper whose usual bedtime is 20:10: the notification at 19:40
        app.openring.core.SleepSounds.Usual early = app.openring.core.SleepSounds.usual(nights(9, 20 * 60 + 10, 5 * 60), ZURICH);
        check("an early sleeper: the notification at 19:40, the stop at 06:00", early.remindMinute() == 19 * 60 + 40 && early.stopMinute() == 6 * 60, early.remindMinute());
        // the next notification: today when still to come, else tomorrow
        long nine = (long) at(LocalDateTime.of(2026, 11, 22, 21, 0)) * 1000;
        eq("at 21:00 the 22:40 notification is today", app.openring.core.SleepSounds.nextAt(22 * 60 + 40, ZURICH, nine), (long) at(LocalDateTime.of(2026, 11, 22, 22, 40)) * 1000);
        long eleven = (long) at(LocalDateTime.of(2026, 11, 22, 23, 0)) * 1000;
        eq("at 23:00 it is tomorrow", app.openring.core.SleepSounds.nextAt(22 * 60 + 40, ZURICH, eleven), (long) at(LocalDateTime.of(2026, 11, 23, 22, 40)) * 1000);
        long exactly = (long) at(LocalDateTime.of(2026, 11, 22, 22, 40)) * 1000;
        eq("at 22:40 itself the next is tomorrow (never twice)", app.openring.core.SleepSounds.nextAt(22 * 60 + 40, ZURICH, exactly), (long) at(LocalDateTime.of(2026, 11, 23, 22, 40)) * 1000);
        // the stop: the usual wake up plus an hour, or 12 hours, whichever first
        double start = at(LocalDateTime.of(2026, 11, 22, 23, 10));
        app.openring.core.SleepSounds.Stop st = app.openring.core.SleepSounds.stopAt(start, 8 * 60 + 5, ZURICH);
        check("started at 23:10: stops at 08:05, not the limit", st.at == at(LocalDateTime.of(2026, 11, 23, 8, 5)) && !st.limit, st.at);
        app.openring.core.SleepSounds.Stop nap = app.openring.core.SleepSounds.stopAt(at(LocalDateTime.of(2026, 11, 22, 14, 0)), 8 * 60 + 5, ZURICH);
        check("started at 14:00: the 12 hour limit at 02:00 comes first", nap.limit && nap.at == at(LocalDateTime.of(2026, 11, 23, 2, 0)), nap.at);
        app.openring.core.SleepSounds.Stop late2 = app.openring.core.SleepSounds.stopAt(at(LocalDateTime.of(2026, 11, 23, 7, 30)), 8 * 60 + 5, ZURICH);
        check("started at 07:30: not stopped within the first hour (the limit then)", late2.limit && late2.at == at(LocalDateTime.of(2026, 11, 23, 19, 30)), late2.at);
        // the night's day: its start plus 12 hours
        eq("a night from 23:10 on Saturday is Sunday's", app.openring.core.SleepSounds.nightDay(at(LocalDateTime.of(2026, 11, 21, 23, 10)), ZURICH), java.time.LocalDate.of(2026, 11, 22));
        eq("a night from 00:30 on Sunday is Sunday's", app.openring.core.SleepSounds.nightDay(at(LocalDateTime.of(2026, 11, 22, 0, 30)), ZURICH), java.time.LocalDate.of(2026, 11, 22));
        eq("listening from 12:00 belongs to the next day", app.openring.core.SleepSounds.nightDay(at(LocalDateTime.of(2026, 11, 22, 12, 0)), ZURICH), java.time.LocalDate.of(2026, 11, 23));
    }

    /** The retention choices: 7 (the default), 30, until deleted; anything else is not a choice. */
    static void keepChoices() {
        check("7, 30 and until deleted (0) are the choices", app.openring.core.SleepSounds.validKeep(7) && app.openring.core.SleepSounds.validKeep(30)
                && app.openring.core.SleepSounds.validKeep(0) && !app.openring.core.SleepSounds.validKeep(14) && !app.openring.core.SleepSounds.validKeep(-1));
        eq("7 days is the default", app.openring.core.SleepSounds.KEEP_DEFAULT, 7);
        eq("three names for three choices", app.openring.core.SleepSounds.KEEP_NAMES.length, app.openring.core.SleepSounds.KEEP_CHOICES.length);
        eq("the 7 day line", app.openring.core.SleepSounds.keepLine(7), "Clips keep their sound for 7 days, then only the mark stays (unless you keep the clip).");
        eq("the until deleted line", app.openring.core.SleepSounds.keepLine(0), "Clips keep their sound until you delete them.");
        check("Settings opened at sleep sounds opens the Phone block", app.openring.core.SettingsBlocks.of("sounds") == app.openring.core.SettingsBlocks.PHONE
                && app.openring.core.SettingsBlocks.startsOpen(app.openring.core.SettingsBlocks.PHONE, "sounds"));
    }

    /** The texts of the card, the consent and the notifications: plain, no dashes, every number explained. */
    static void texts1b() {
        List<app.openring.core.SleepSounds.Mark> m = new ArrayList<app.openring.core.SleepSounds.Mark>();
        double t = at(LocalDateTime.of(2026, 11, 22, 0, 41));
        m.add(new app.openring.core.SleepSounds.Mark(t, t + 3.2, SoundDetector.TALK, true, false));
        m.add(new app.openring.core.SleepSounds.Mark(t + 5460, t + 5460 + 14 * 60, SoundDetector.SNORE, true, false));
        m.add(new app.openring.core.SleepSounds.Mark(t + 10560, t + 10560.6, SoundDetector.COUGH, true, true));
        m.add(new app.openring.core.SleepSounds.Mark(t + 15720, t + 15724, SoundDetector.TALK, false, false));
        eq("the night's line", app.openring.core.SleepSounds.summary(m), "4 sounds: snoring 14 min, 1 cough like sound, 2 talking or other sounds.");
        eq("a quiet night", app.openring.core.SleepSounds.summary(new ArrayList<app.openring.core.SleepSounds.Mark>()), app.openring.core.SleepSounds.QUIET);
        List<app.openring.core.SleepSounds.Mark> one = new ArrayList<app.openring.core.SleepSounds.Mark>();
        one.add(new app.openring.core.SleepSounds.Mark(t, t + 20, SoundDetector.SNORE, true, false));
        eq("a short snoring stretch", app.openring.core.SleepSounds.summary(one), "1 sound: snoring under 1 min.");
        eq("a talking row", app.openring.core.SleepSounds.row(m.get(0), ZURICH), "00:41 Talking or other sound, 3 s");
        eq("a snoring row", app.openring.core.SleepSounds.row(m.get(1), ZURICH), "02:12 Snoring, 14 min");
        eq("a cough row", app.openring.core.SleepSounds.row(m.get(2), ZURICH), "03:37 Cough like sound, 1 s");
        eq("a row whose sound was deleted", app.openring.core.SleepSounds.row(m.get(3), ZURICH), "05:03 Talking or other sound, 4 s (sound deleted)");
        eq("listening now", app.openring.core.SleepSounds.listeningLine(at(LocalDateTime.of(2026, 11, 21, 23, 12)), 3, ZURICH), "Listening since 23:12, 3 sounds so far.");
        eq("space used", app.openring.core.SleepSounds.spaceLine(12, 640 * 1024), "12 clips, 0.6 MB");
        eq("space used, small", app.openring.core.SleepSounds.spaceLine(1, 40 * 1024), "1 clip, under 0.1 MB");
        eq("space used, none", app.openring.core.SleepSounds.spaceLine(0, 0), "No clips");
        eq("a night Android ended", app.openring.core.SleepSounds.endedLine(app.openring.core.SleepSounds.ENDED_SYSTEM, at(LocalDateTime.of(2026, 11, 22, 3, 40)), ZURICH),
                "Stopped at 03:40: Android ended the listening.");
        check("a night stopped by hand or at its time has no such line", app.openring.core.SleepSounds.endedLine(app.openring.core.SleepSounds.ENDED_HAND, t, ZURICH) == null
                && app.openring.core.SleepSounds.endedLine(app.openring.core.SleepSounds.ENDED_TIME, t, ZURICH) == null
                && app.openring.core.SleepSounds.endedLine(null, t, ZURICH) == null);
        eq("12 minutes of silence", app.openring.core.SleepSounds.gapLine(12 * 60), "Not listening for 12 min: another app used the microphone.");
        check("under a minute of silence is not worth a line", app.openring.core.SleepSounds.gapLine(59) == null && app.openring.core.SleepSounds.gapLine(Double.NaN) == null);
        eq("tested on", app.openring.core.SleepSounds.testedLine((long) at(LocalDateTime.of(2026, 11, 22, 22, 0)), 22.4, 11.2, ZURICH),
                "Microphone tested on 22 Nov: your voice was 22 dB above the room.");
        eq("not tested", app.openring.core.SleepSounds.testedLine(0, Double.NaN, 10, ZURICH), "Microphone not tested yet, so a sound is kept from 10 dB above the room.");
        eq("the margin line", app.openring.core.SleepSounds.marginLine(22.4), "Your voice was 22 dB above the room.");
        eq("the threshold line", app.openring.core.SleepSounds.thresholdLine(11.2), "A sound is kept from 11.2 dB above the room.");
        eq("the no diagnosis sentence (Design review)", app.openring.core.SleepSounds.NO_DIAGNOSIS, "Snoring minutes are not a test for sleep apnea. If breathing pauses worry you, ask a doctor.");
        check("the consent names backups (Design review)", app.openring.core.SleepSounds.CONSENT.contains("Times and kinds of sounds are in backups, the sounds themselves never."));
        check("the consent names the person next to you", app.openring.core.SleepSounds.CONSENT.contains("If someone sleeps next to you, they will be recorded too"));
        eq("the test sentence (owner's wish)", app.openring.core.SleepSounds.TEST_SENTENCE, "I am testing the sleep sounds of Open Ring.");
        eq("the kinds' names", app.openring.core.SleepSounds.kindName(SoundDetector.SNORE) + "|" + app.openring.core.SleepSounds.kindName(SoundDetector.COUGH) + "|"
                + app.openring.core.SleepSounds.kindName(SoundDetector.TALK), "Snoring|Cough like sound|Talking or other sound");
        check("the stored values of why a night stopped", app.openring.core.SleepSounds.ENDED_LIMIT.equals("12 h") && app.openring.core.SleepSounds.ENDED_SYSTEM.equals("system"));
        String[] all = {app.openring.core.SleepSounds.ABOUT, app.openring.core.SleepSounds.NOTHING, app.openring.core.SleepSounds.QUIET, app.openring.core.SleepSounds.COUNTS_NOTE,
            app.openring.core.SleepSounds.BATTERY, app.openring.core.SleepSounds.NO_DIAGNOSIS, app.openring.core.SleepSounds.DEMO_NO_RECORD, app.openring.core.SleepSounds.MIC_OFF,
            app.openring.core.SleepSounds.MIC_SETTINGS, app.openring.core.SleepSounds.MIC_REFUSED, app.openring.core.SleepSounds.NOT_STARTED, app.openring.core.SleepSounds.CONSENT,
            app.openring.core.SleepSounds.NOTE_TEXT, app.openring.core.SleepSounds.REMIND_TEXT, app.openring.core.SleepSounds.DELETE_NIGHT_ASK, app.openring.core.SleepSounds.KEPT_NOTE,
            app.openring.core.SleepSounds.TEST_ROOM, app.openring.core.SleepSounds.TEST_PLAYED, app.openring.core.SleepSounds.TEST_PROCESSED, app.openring.core.SleepSounds.THRESHOLD_NOTE,
            app.openring.core.SleepSounds.MARGIN_NOTE, app.openring.core.SleepSounds.keepLine(7), app.openring.core.SleepSounds.summary(m)};
        boolean plain = true;
        for (String s : all) plain &= !s.contains(" - ") && !s.contains("–") && !s.contains("—");
        check("no dashes as punctuation in the sleep sound texts", plain);
    }

    /** The WAV file the encoder falls back to: a 44 byte header and the samples, little endian. */
    static void wavFile() {
        short[] s = {0, 1, -1, 32767, -32768};
        byte[] w = app.openring.core.SleepSounds.wav(s, 1, 4, 16000);
        eq("44 bytes of header and 2 per sample", w.length, 44 + 8);
        eq("RIFF and WAVE", new String(w, 0, 4) + new String(w, 8, 4) + new String(w, 12, 4) + new String(w, 36, 4), "RIFFWAVEfmt data");
        eq("the RIFF size", (w[4] & 0xff) | (w[5] & 0xff) << 8, 36 + 8);
        eq("16 kHz", (w[24] & 0xff) | (w[25] & 0xff) << 8 | (w[26] & 0xff) << 16, 16000);
        eq("one channel, 16 bit", "" + w[22] + w[34], "116");
        eq("the first sample (1), little endian", "" + w[44] + w[45], "10");
        eq("the last sample (-32768)", "" + (w[50] & 0xff) + " " + (w[51] & 0xff), "0 128");
    }

    /** The demo's clips are tones made in code: deterministic, of their length, never clipped, each found by the detector. */
    static void demoTones() {
        for (String kind : new String[] {SoundDetector.TALK, SoundDetector.SNORE, SoundDetector.COUGH}) {
            short[] a = app.openring.core.SleepSounds.demoTone(kind, 8), b = app.openring.core.SleepSounds.demoTone(kind, 8);
            eq("demo " + kind + ": 8 s at 16 kHz", a.length, 8 * R);
            check("demo " + kind + ": the same every time", Arrays.equals(a, b));
            int max = 0;
            for (short v : a) max = Math.max(max, Math.abs((int) v));
            check("demo " + kind + ": loud enough and never clipped", max > 3000 && max < 32767, max);
            SoundDetector d = new SoundDetector(T0);
            d.feed(a);
            d.finish();
            check("demo " + kind + ": the detector hears it", !d.marks().isEmpty(), d.marks());
        }
    }

    /** The last seconds of audio: older samples are gone, a copy gives what is still held. */
    static void audioRing() {
        app.openring.core.analysis.AudioRing r = new app.openring.core.analysis.AudioRing(10);
        short[] s = new short[25];
        for (int i = 0; i < s.length; i++) s[i] = (short) i;
        r.add(s, 0, 7);
        r.add(s, 7, 18);
        eq("25 samples added", r.total(), 25L);
        eq("it holds 10", r.capacity(), 10);
        eq("the oldest held is 15", r.oldest(), 15L);
        eq("a copy from 12 to 20 gives 15 to 19", Arrays.toString(r.copy(12, 20)), "[15, 16, 17, 18, 19]");
        eq("a copy past the newest stops at it", Arrays.toString(r.copy(22, 30)), "[22, 23, 24]");
        eq("a copy of what is gone is empty", r.copy(0, 10).length, 0);
        r.clear();
        eq("cleared: nothing held", r.total() + " " + r.copy(0, 5).length, "0 0");
        boolean refused = false;
        try {
            new app.openring.core.analysis.AudioRing(0);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("a ring of no samples is refused", refused);
    }

    static String describe(SoundDetector d) {
        StringBuilder b = new StringBuilder();
        for (Mark m : d.marks()) b.append(m).append(" | ").append(m.clip()).append('\n');
        for (Gap g : d.gaps()) b.append(g).append('\n');
        return b.toString();
    }
}
