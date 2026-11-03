package app.openring.core.withings;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Made up watch messages for demo mode and the tests (issue #251, phase 1b): what a Steel HR could send, built with
 * the same frame code, so the demo's Watch page goes through the real decoder. Every value is invented; nothing comes
 * from a real watch. Also the builders of the watch's answers that the tests use.
 */
public final class WatchDemo {
    private WatchDemo() {}

    /** The made up watch's address and name. */
    public static final String ADDRESS = "00:00:5E:00:53:51", NAME = "Steel HR (demo)";

    // ---------------------------------------------------------------- answers as the watch sends them

    public static byte[] helloReply(String name, long firmware) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8), mac = "00:00:00:00:00:00".getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0); b.write(0); b.write(0); b.write(1);
        b.write(n.length);
        b.write(n, 0, n.length);
        b.write(mac.length);
        b.write(mac, 0, mac.length);
        b.write(0);
        for (int i = 0; i < 4; i++) b.write(0);
        b.write(0);
        for (int i = 0; i < 4; i++) b.write(0);
        byte[] fw = Frame.be32(firmware);
        b.write(fw, 0, 4);
        for (int i = 0; i < 4; i++) b.write(0);
        return Frame.message(Proto.HELLO, Frame.part(Proto.HELLO_REPLY, b.toByteArray()));
    }

    public static byte[] empty(int type) {
        return Frame.message(type);
    }

    public static byte[] batteryReply(int pct, int state, int mv) {
        byte[] v = new byte[6];
        v[0] = (byte) pct;
        v[1] = (byte) state;
        Frame.put32(v, 2, mv);
        return Frame.message(Proto.BATTERY, Frame.part(Proto.BATTERY_PART, v));
    }

    public static byte[] time(long t) {
        return Frame.part(Proto.REC_TIME, Frame.be32(t));
    }

    public static byte[] duration(int s) {
        byte[] v = new byte[2];
        Frame.put16(v, 0, s);
        return Frame.part(Proto.REC_DURATION, v);
    }

    public static byte[] steps(int steps, int metres) {
        byte[] v = new byte[14];
        Frame.put16(v, 0, steps);
        Frame.put32(v, 2, metres);
        return Frame.part(Proto.REC_STEPS, v);
    }

    public static byte[] calories(int kcal) {
        byte[] v = new byte[4];
        Frame.put16(v, 0, kcal);
        return Frame.part(Proto.REC_CALORIES, v);
    }

    public static byte[] sleep(int code) {
        byte[] v = new byte[2];
        Frame.put16(v, 0, code);
        return Frame.part(Proto.REC_SLEEP, v);
    }

    public static byte[] workout(int code) {
        byte[] v = new byte[2];
        Frame.put16(v, 0, code);
        return Frame.part(Proto.REC_WORKOUT, v);
    }

    public static byte[] heart(int bpm) {
        return Frame.part(Proto.REC_HEART_RATE, new byte[] {(byte) bpm, 0, 0, 0});
    }

    public static byte[] end() {
        return Frame.part(Proto.END, new byte[0]);
    }

    /** Records (each a list of parts) as the messages of one data answer, {@code per} records each, the end part in the last. */
    public static List<byte[]> answer(int type, List<byte[][]> records, int per) {
        List<byte[]> out = new ArrayList<byte[]>();
        int i = 0;
        do {
            List<byte[]> parts = new ArrayList<byte[]>();
            for (int k = 0; k < per && i < records.size(); k++, i++) for (byte[] p : records.get(i)) parts.add(p);
            if (i >= records.size()) parts.add(end());
            out.add(Frame.message(type, parts.toArray(new byte[0][])));
        } while (i < records.size());
        return out;
    }

    // ---------------------------------------------------------------------------- the demo's history

    /** One made up message as stored: its session (start in ms), place in it, when it came (unix seconds). */
    public static final class Msg {
        public final long session, captured;
        public final int seq;
        public final byte[] body;

        Msg(long session, int seq, long captured, byte[] body) {
            this.session = session;
            this.seq = seq;
            this.captured = captured;
            this.body = body;
        }
    }

    static long at(LocalDate day, double hours, ZoneId zone) {
        long s = (long) Math.floor(hours * 3600);
        return ZonedDateTime.of(day.atStartOfDay(), zone).plusSeconds(s).toEpochSecond();
    }

    /**
     * Made up sessions for the {@code days} days up to {@code today}: one about 07:05 each morning with what came since
     * the morning before, and one at {@code now} with today so far. Sleep from about 23:10 to 06:50 in cycles of light,
     * deep and REM, steps every 15 minutes in the day, a run or a ride every third day, heart rate every 30 minutes
     * (every 5 in a workout), and a battery that falls about 3 % a day and was charged once.
     */
    public static List<Msg> history(LocalDate today, int days, long now, ZoneId zone, Random r) {
        List<long[]> idx = new ArrayList<long[]>();
        List<byte[][]> recs = new ArrayList<byte[][]>();
        List<byte[][]> hrs = new ArrayList<byte[][]>();
        List<Long> hrT = new ArrayList<Long>();
        for (int d = days; d >= 0; d--) {
            LocalDate day = today.minusDays(d);
            // the night that ends this morning
            long t = at(day, -1 + (10 + r.nextInt(25)) / 60.0, zone);
            long wake = at(day, 6.5 + r.nextInt(40) / 60.0, zone);
            int cycle = 0;
            while (t < wake) {
                int[][] plan = {{Records.LIGHT, 20 + r.nextInt(10)}, {Records.DEEP, cycle < 2 ? 20 + r.nextInt(10) : 5 + r.nextInt(8)},
                    {Records.LIGHT, 15 + r.nextInt(10)}, {Records.REM, cycle < 2 ? 10 + r.nextInt(8) : 20 + r.nextInt(10)}};
                for (int[] p : plan) {
                    if (t >= wake) break;
                    int dur = (int) Math.min(p[1] * 60L, wake - t);
                    recs.add(new byte[][] {time(t), duration(dur), sleep(p[0])});
                    idx.add(new long[] {t});
                    t += dur;
                }
                cycle++;
            }
            // steps through the day, a workout every third day
            boolean workout = d % 3 == 1;
            for (double h = 7; h < 22.9; h += 0.25) {
                long s = at(day, h, zone);
                if (workout && h >= 18 && h < 18.75) continue;
                int base = h >= 8 && h < 8.5 || h >= 17.25 && h < 17.75 ? 700 : h >= 12 && h < 12.5 ? 450 : 120;
                int st = Math.max(0, base + r.nextInt(250) - 100);
                recs.add(new byte[][] {time(s), duration(900), steps(st, (int) (st * 0.72)), calories(st / 25)});
                idx.add(new long[] {s});
            }
            if (workout) {
                long s = at(day, 18, zone);
                boolean run = (d / 3) % 2 == 0;
                int dur = run ? 35 * 60 + r.nextInt(300) : 50 * 60 + r.nextInt(600);
                int st = run ? 5000 + r.nextInt(600) : 0;
                int m = run ? 5400 + r.nextInt(700) : 14000 + r.nextInt(3000);
                recs.add(new byte[][] {time(s), duration(dur), workout(run ? 2 : 6), steps(st, m), calories(run ? 360 + r.nextInt(60) : 420 + r.nextInt(80))});
                idx.add(new long[] {s});
                for (long u = s; u < s + dur; u += 300) {
                    hrs.add(new byte[][] {time(u), heart((run ? 145 : 128) + r.nextInt(18))});
                    hrT.add(u);
                }
            }
            // heart rate every 30 minutes
            for (double h = 0; h < 24; h += 0.5) {
                long u = at(day, h, zone);
                if (workout && h >= 18 && h < 19) continue;
                boolean night = h < 6.5 || h >= 23;
                hrs.add(new byte[][] {time(u), heart(night ? 52 + r.nextInt(8) : 64 + r.nextInt(18))});
                hrT.add(u);
            }
        }
        // sessions: every morning at 07:05 (the morning that has passed), the last one now
        List<Msg> out = new ArrayList<Msg>();
        long from = Long.MIN_VALUE;
        int pct = 97;
        for (int d = days - 1; d >= -1; d--) {
            long cap = d >= 0 ? at(today.minusDays(d), 7 + 5 / 60.0, zone) : now - 600;
            if (d >= 0 && cap > now - 600) continue;
            if (cap <= from) continue;
            List<byte[][]> part = new ArrayList<byte[][]>(), hpart = new ArrayList<byte[][]>();
            for (int i = 0; i < recs.size(); i++) {
                long t = idx.get(i)[0];
                if (t >= from && t < cap - 900) part.add(recs.get(i));
            }
            for (int i = 0; i < hrs.size(); i++) if (hrT.get(i) >= from && hrT.get(i) < cap - 900) hpart.add(hrs.get(i));
            long session = cap * 1000;
            int seq = 0;
            out.add(new Msg(session, seq++, cap, helloReply(NAME, 1971)));
            out.add(new Msg(session, seq++, cap, empty(Proto.CONNECT)));
            if (d == 17) pct = 100; // charged once
            out.add(new Msg(session, seq++, cap, batteryReply(pct, 2, 3000 + pct * 10)));
            pct = Math.max(5, pct - 3 + (r.nextInt(3) == 0 ? -1 : 0));
            out.add(new Msg(session, seq++, cap, empty(Proto.SET_TIME)));
            for (byte[] m : answer(Proto.ACTIVITY, part, 12)) out.add(new Msg(session, seq++, cap, m));
            for (byte[] m : answer(Proto.HEART_RATE, hpart, 24)) out.add(new Msg(session, seq++, cap, m));
            out.add(new Msg(session, seq++, cap, empty(Proto.SYNC_DONE)));
            from = cap - 900;
        }
        return out;
    }
}
