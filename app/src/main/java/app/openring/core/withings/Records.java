package app.openring.core.withings;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the watch's stored messages into rows (issue #251, phase 1b). Pure: the same messages always give the same
 * rows, so the stored rows can be worked out again from the raw messages whenever this decoder improves
 * ({@link #VERSION}). An answer to an activity or heart rate request holds records one after the other; each starts
 * with a time part, and the parts up to the next time part belong to it. A record is then a workout (it names a
 * workout type, or the watch marks it as running or swimming), a piece of sleep (it has a sleep stage), or steps; a
 * heart rate part in any record gives a heart rate at its time. Times are unix seconds (UTC). Version 2 (watch setup):
 * the movement answers ({@link Proto#MOVEMENT}) too, and per record the intensity of its calories part and the metres
 * climbed and descended of its movement part ({@link Moves}). Version 3 (the owner's first real data, 11 November): the
 * distance of the movement part is in centimetres (about 80 per step), so it is stored as whole metres.
 */
public final class Records {
    private Records() {}

    /** Raised whenever the rules below change: every stored message is decoded again (old rows stay until then). */
    public static final int VERSION = 3;

    /** Heart rates outside this range are damaged bytes, as for the ring (30 to 220 bpm). */
    public static final int HR_MIN = 30, HR_MAX = 220;

    /** Workout codes for the watch's own running and swimming marks (the same codes as the workout list). */
    public static final int RUN = 2, SWIM = 7;

    /**
     * Sleep stage codes as the watch sends them, the numbers of Withings' public sleep data: 0 awake, 1 light, 2 deep,
     * 3 REM. (Gadgetbridge reads 0 and any unknown code as light; the owner's first real night showed 1 as the most common
     * code, which only fits light sleep.) Other codes are kept as they came and show as no data.
     */
    public static final int AWAKE = 0, LIGHT = 1, DEEP = 2, REM = 3;

    /** Whole metres of a distance the watch sends in centimetres (half a metre rounds up). */
    public static int metres(long cm) {
        return (int) Math.min(Integer.MAX_VALUE, (Math.max(0, cm) + 50) / 100);
    }

    /**
     * The newest record time in the messages of one data answer (one request in one session, its own type), or -1 when
     * the answer did not reach its end part: only a whole answer may move where the next request of that kind starts.
     * 0 for a whole answer without records. Messages the watch sent on its own are not part of an answer.
     */
    public static long newestComplete(List<byte[]> answer) {
        boolean end = false;
        long newest = 0;
        for (byte[] m : answer) {
            if (Frame.fromWatch(m)) continue;
            for (Frame.Part p : Frame.parts(m)) {
                if (p.type == Proto.END) end = true;
                else if (p.type == Proto.REC_TIME && p.value.length >= 4) newest = Math.max(newest, Frame.u32(p.value, 0));
            }
        }
        return end ? newest : -1;
    }

    public static final class Steps {
        public final long start;
        public final int dur, steps, metres, kcal;

        public Steps(long start, int dur, int steps, int metres, int kcal) {
            this.start = start;
            this.dur = dur;
            this.steps = steps;
            this.metres = metres;
            this.kcal = kcal;
        }
    }

    public static final class Heart {
        public final long t;
        public final int bpm;

        public Heart(long t, int bpm) {
            this.t = t;
            this.bpm = bpm;
        }
    }

    public static final class Sleep {
        public final long start;
        public final int dur, stage;

        public Sleep(long start, int dur, int stage) {
            this.start = start;
            this.dur = dur;
            this.stage = stage;
        }
    }

    public static final class Workout {
        public final long start;
        public final int dur, kind, steps, metres, kcal;

        public Workout(long start, int dur, int kind, int steps, int metres, int kcal) {
            this.start = start;
            this.dur = dur;
            this.kind = kind;
            this.steps = steps;
            this.metres = metres;
            this.kcal = kcal;
        }
    }

    /** A record's intensity (the number after the calories) and metres climbed and descended, when it had any. */
    public static final class Moves {
        public final long start;
        public final int met, up, down;

        public Moves(long start, int met, int up, int down) {
            this.start = start;
            this.met = met;
            this.up = up;
            this.down = down;
        }
    }

    public static final class Battery {
        public final long t;
        public final int pct, state, mv;

        public Battery(long t, int pct, int state, int mv) {
            this.t = t;
            this.pct = pct;
            this.state = state;
            this.mv = mv;
        }
    }

    /** Everything decoded from some messages; the hello's name and firmware when one was among them. */
    public static final class Decoded {
        public final List<Steps> steps = new ArrayList<Steps>();
        public final List<Heart> heart = new ArrayList<Heart>();
        public final List<Sleep> sleep = new ArrayList<Sleep>();
        public final List<Workout> workouts = new ArrayList<Workout>();
        public final List<Battery> battery = new ArrayList<Battery>();
        public final List<Moves> moves = new ArrayList<Moves>();
        public String name;
        public long firmware = -1;
        /** Records that had a time but nothing this decoder knows (counted for the log, never shown). */
        public int unknown;
    }

    /** Decodes one message the watch sent at {@code captured} (unix seconds) into {@code out}. */
    public static void decode(byte[] msg, long captured, Decoded out) {
        int type = Frame.type(msg);
        if (type == Proto.HELLO) {
            Proto.Hello h = Proto.hello(msg);
            if (h.name != null) out.name = h.name;
            if (h.firmware >= 0) out.firmware = h.firmware;
        } else if (type == Proto.BATTERY) {
            int[] b = Proto.battery(msg);
            if (b != null && b[0] <= 100) out.battery.add(new Battery(captured, b[0], b[1], b[2]));
        } else if (type == Proto.ACTIVITY || type == Proto.HEART_RATE || type == Proto.MOVEMENT) {
            Rec r = null;
            for (Frame.Part p : Frame.parts(msg)) {
                if (p.type == Proto.REC_TIME) {
                    if (r != null) r.add(out);
                    r = p.value.length >= 4 ? new Rec(Frame.u32(p.value, 0)) : null;
                } else if (r != null) {
                    r.part(p);
                }
            }
            if (r != null) r.add(out);
        }
    }

    /** Decodes messages in order, each with its captured time. */
    public static Decoded decode(List<byte[]> msgs, List<Long> captured) {
        Decoded d = new Decoded();
        for (int i = 0; i < msgs.size(); i++) decode(msgs.get(i), captured.get(i), d);
        return d;
    }

    /** One record while its parts are read. */
    private static final class Rec {
        final long t;
        int dur, steps, metres, kcal, workout = -1, sleep = -1, bpm = -1, met, up, down;
        boolean moved;

        Rec(long t) {
            this.t = t;
        }

        void part(Frame.Part p) {
            byte[] v = p.value;
            switch (p.type) {
                case Proto.REC_DURATION:
                    if (v.length >= 2) dur = Frame.u16(v, 0);
                    break;
                case Proto.REC_STEPS:
                    if (v.length >= 6) {
                        steps = Frame.u16(v, 0);
                        metres = metres(Frame.u32(v, 2));
                        moved = true;
                    }
                    if (v.length >= 14) {
                        up = (int) Math.min(Integer.MAX_VALUE, Frame.u32(v, 6));
                        down = (int) Math.min(Integer.MAX_VALUE, Frame.u32(v, 10));
                    }
                    break;
                case Proto.REC_CALORIES:
                case Proto.REC_CALORIES_2:
                    if (v.length >= 2) kcal = Frame.u16(v, 0);
                    if (v.length >= 4) met = Frame.u16(v, 2);
                    break;
                case Proto.REC_SLEEP:
                    if (v.length >= 2) sleep = Frame.u16(v, 0);
                    break;
                case Proto.REC_WORKOUT:
                    if (v.length >= 2) workout = Frame.u16(v, 0);
                    break;
                case Proto.REC_RUN:
                    if (workout < 0) workout = RUN;
                    break;
                case Proto.REC_SWIM:
                    if (workout < 0) workout = SWIM;
                    break;
                case Proto.REC_HEART_RATE:
                    if (v.length >= 1) bpm = v[0] & 0xff;
                    break;
                default:
                    break; // walking level and parts not known yet: kept in the raw message only
            }
        }

        void add(Decoded out) {
            boolean any = false;
            if (bpm >= HR_MIN && bpm <= HR_MAX) {
                out.heart.add(new Heart(t, bpm));
                any = true;
            }
            if (workout >= 0) {
                out.workouts.add(new Workout(t, dur, workout, steps, metres, kcal));
                any = true;
            } else if (sleep >= 0) {
                out.sleep.add(new Sleep(t, dur, sleep));
                any = true;
            } else if (moved || kcal > 0) {
                out.steps.add(new Steps(t, dur, steps, metres, kcal));
                any = true;
            }
            if (any && (met > 0 || up > 0 || down > 0)) out.moves.add(new Moves(t, met, up, down));
            if (!any) out.unknown++;
        }
    }
}
