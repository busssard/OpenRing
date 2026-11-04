package app.openring.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.openring.core.Protocol;
import app.openring.core.RingGate;
import app.openring.core.withings.Frame;
import app.openring.core.withings.Records;
import app.openring.core.withings.Session;
import app.openring.core.withings.WatchSetup;
import app.openring.data.Db;
import app.openring.data.Prefs;
import app.openring.data.WatchStore;

/**
 * One sync with the Withings watch (issue #251, phase 1b): connect, download what is new, set its clock, let go.
 * Its own link, not {@link GattLink} (which is made for the ring), and its own lock ({@link RingGate#watch}). It also
 * holds the ring lock, taken without waiting ({@link RingManager#whileRingFree(Runnable, String, boolean)}, without marking the
 * ring busy, so a sync by hand is not refused), so the phone never
 * keeps two links, and gives it back between two messages as soon as a ring task asks for it ({@link RingGate#ringWanted}):
 * the ring always wins. The order of the messages is {@code core/withings/Session}; every message is stored as it comes
 * ({@code data/WatchStore}) and decoded at the end. The log names steps, message types and lengths, never a value.
 */
@SuppressLint("MissingPermission")
public final class WatchSync {
    private WatchSync() {}

    public static final UUID SERVICE = UUID.fromString("00000020-5749-5448-0037-000000000000");
    /** The characteristic the requests go to and the answers come from (public notes; the Watch check showed write and notify). */
    public static final UUID CHANNEL = UUID.fromString("00000024-5749-5448-0037-000000000000");
    /** The MTU asked for (the public notes use 119); the watch may give less, pieces follow what it gives. */
    static final int MTU = 119;
    static final long CONNECT_MS = 15000, STEP_MS = 5000;

    public static final String RUNNING = "Watch sync running, the ring gets Bluetooth back at once when it needs it";
    public static final String BUSY = "The ring is busy right now. The watch syncs once it is free, or try again in a minute.";
    public static final String NO_WATCH = "No watch added yet. Check a Withings watch in Settings, Watch, then tap Use this watch for syncing.";
    public static final String DEMO = "Demo mode: syncing is off";
    public static final String BLUETOOTH_OFF = "Bluetooth is off. Switch it on to sync the watch.";
    public static final String ALREADY = "A watch sync is already running.";
    public static final String LONG_RUN = "Re analysing your history, the watch syncs after it.";
    public static final String NO_SERVICE = "This device did not show the Withings service. Check the watch again in Settings, Watch.";
    public static final String NOT_CONNECTED = "The watch did not connect. Keep it next to the phone, press its button and try again.";
    public static final String FAILED = "The watch sync failed. Settings, Help, Data check and log shows why.";

    /** Set when Android stops the background job; the session ends between two messages. */
    private static volatile boolean stopAsked;

    public static void stop() {
        stopAsked = true;
    }

    /** True while a watch sync runs. */
    public static boolean running() {
        return RingGate.RING.watch.isLocked();
    }

    /**
     * One sync. Blocks for up to about two minutes; call it from a background thread. Returns null when everything was
     * asked and answered, else the plain sentence of what happened (also stored for Settings and the Data check). A sync
     * that did not start because the ring was busy is no attempt: the background job tries again at its next run.
     */
    public static String run(final Context ctx) {
        return run(ctx, SYNC, null, null);
    }

    /** What a session does: a sync, the first setup (then the first sync), the alarm only, the hand calibration. */
    public static final int SYNC = 0, SETUP = 1, ALARM = 2, HANDS = 3;

    /**
     * The first setup after pairing (issue #251, watch setup): hello, sign in, the setup from the profile, setup finished
     * and the first sync, in one connection. {@code progress} hears each step. Null when all was done, else the sentence.
     */
    public static String setup(Context ctx, Session.Progress progress) {
        return run(ctx, SETUP, progress, null);
    }

    /** Sends the alarm as the app has it ({@link Prefs#watchAlarm}) in a short session of its own. */
    public static String alarm(Context ctx) {
        return run(ctx, ALARM, null, null);
    }

    /**
     * The hand calibration: the session stays open while {@code more} hands it moves, up to 5 minutes; at the end (also an
     * early one) calibration mode is switched off and the phone's time sent.
     */
    public static String hands(Context ctx, Session.More more, Session.Progress progress) {
        return run(ctx, HANDS, progress, more);
    }

    /** What the watch is told about the person, from the app's profile and the phone's settings. */
    public static WatchSetup.Profile profile(Context ctx) {
        Prefs p = Prefs.of(ctx);
        WatchSetup.Profile w = new WatchSetup.Profile();
        w.age = p.age();
        w.sex = p.sex();
        w.weightKg = p.weightKg();
        w.heightCm = p.heightCm();
        w.goal = p.watchGoal();
        w.metric = p.watchMetric();
        w.h24 = android.text.format.DateFormat.is24HourFormat(ctx);
        w.language = java.util.Locale.getDefault().getLanguage();
        w.year = java.time.LocalDate.now().getYear();
        return w;
    }

    static String run(final Context ctx, final int mode, final Session.Progress progress, final Session.More more) {
        final Prefs p = Prefs.of(ctx);
        final RingManager ring = RingManager.get(ctx);
        if (p.demo()) return DEMO;
        final String address = p.watchAddress();
        if (address == null) return NO_WATCH;
        final BluetoothAdapter a = RingScanner.adapter(ctx);
        if (a == null || !a.isEnabled()) return BLUETOOTH_OFF;
        if (!RingGate.RING.watch.tryLock()) return ALREADY;
        try {
            stopAsked = false;
            final String[] out = {null};
            final long attempt = System.currentTimeMillis() / 1000;
            boolean ran = ring.whileRingFree(new Runnable() {
                @Override
                public void run() {
                    try {
                        out[0] = locked(ctx, a, address, ring, mode, progress, more);
                    } catch (RuntimeException e) {
                        // Bluetooth switched off meanwhile, a permission taken back, a full disk
                        ring.log("watch: failed (" + e.getClass().getSimpleName() + ")");
                        out[0] = FAILED;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        out[0] = Session.STOPPED;
                    }
                }
            }, RUNNING, false);
            if (!ran) {
                ring.log("watch: not synced, the ring is busy");
                return BUSY;
            }
            boolean ok = out[0] == null;
            // a sync or the setup (which ends with the first sync) counts as a sync; the alarm and the hands do not
            if ((mode == SYNC || mode == SETUP) && !DEMO.equals(out[0]) && !LONG_RUN.equals(out[0])) p.setWatchResult(attempt, ok, out[0]);
            return out[0];
        } finally {
            RingGate.RING.watch.unlock();
        }
    }

    /** The sync itself, with the ring lock and the watch lock held. */
    private static String locked(Context ctx, BluetoothAdapter a, final String address, final RingManager ring, int mode, Session.Progress progress,
            Session.More more) throws InterruptedException {
        final Db db = Db.get(ctx);
        // demo mode was turned on meanwhile: never into the demo file (the ring's rule, issue #185)
        if (Db.DEMO.equals(db.fileName) || Prefs.of(ctx).demo()) return DEMO;
        if (!db.holdForRing()) return LONG_RUN;
        Link link = new Link(ring);
        try {
            if (WatchStore.ensureDecoded(db)) ring.log("watch: a new decoder, every stored message decoded again");
            BluetoothDevice device = a.getRemoteDevice(address);
            ring.log("watch: paired with this phone: " + (device.getBondState() == BluetoothDevice.BOND_BONDED ? "yes" : "no"));
            String problem = link.open(ctx, device);
            if (problem != null) return problem;
            final long session = System.currentTimeMillis();
            final double captured = session / 1000.0;
            long now = session / 1000;
            Session.Plan plan = plan(now, WatchStore.since(db, address, false, now), WatchStore.since(db, address, true, now));
            plan.address = address;
            plan.progress = progress;
            final Prefs prefs = Prefs.of(ctx);
            WatchSetup.Profile who = profile(ctx);
            WatchSetup.Alarm alarm = prefs.watchAlarm();
            if (mode == SETUP) {
                plan.steps = WatchSetup.setup(plan, who, alarm, plan.activitySince);
                plan.limitMs = 180000;
            } else if (mode == ALARM) {
                plan.steps = WatchSetup.alarmOnly(alarm);
            } else if (mode == HANDS) {
                plan.steps = WatchSetup.handsStart();
                plan.more = more;
                plan.last = WatchSetup.handsEnd(plan);
                plan.limitMs = 300000;
            } else {
                plan.steps = WatchSetup.sync(plan, who, prefs.watchAlarmPending() ? alarm : null, plan.activitySince);
            }
            ring.log("watch: " + (mode == SETUP ? "setup" : mode == ALARM ? "alarm" : mode == HANDS ? "hand calibration" : "sync") + ", " + plan.steps.size() + " steps"
                    + (who.complete() ? "" : ", the user is not sent (sex not given)"));
            final int[] seq = {0};
            Session.Result r = Session.run(link, plan, new Session.Gate() {
                @Override
                public boolean ringWanted() {
                    return RingGate.RING.ringWanted();
                }

                @Override
                public boolean stopped() {
                    return stopAsked || Thread.currentThread().isInterrupted();
                }
            }, new Session.Sink() {
                @Override
                public void received(byte[] msg) throws IOException {
                    try {
                        WatchStore.addRaw(db, session, seq[0]++, address, msg, captured);
                    } catch (RuntimeException e) {
                        throw new IOException(e);
                    }
                }
            }, new Session.Log() {
                @Override
                public void log(String line) {
                    ring.log(line);
                }
            });
            link.close();
            if (r.done.contains(WatchSetup.ALARM_ON)) prefs.setWatchAlarmSent();
            if (r.done.contains(WatchSetup.FINISHED)) prefs.setWatchSetupAt(now);
            if (seq[0] > 0) {
                Records.Decoded d = WatchStore.decodeSession(db, session);
                WatchStore.noteDevice(db, address, d.name, d.firmware >= 0 ? String.valueOf(d.firmware) : null, captured);
                // counts only, never a value
                ring.log("watch: stored " + seq[0] + " messages, decoded " + d.steps.size() + " step records, " + d.heart.size() + " heart rates, "
                        + d.sleep.size() + " sleep pieces, " + d.workouts.size() + " workouts, " + d.battery.size() + " charge readings, " + d.moves.size() + " intensity and climb records"
                        + (d.unknown > 0 ? ", " + d.unknown + " records not known yet" : ""));
            }
            return r.ended;
        } finally {
            link.close();
            db.releaseForRing();
        }
    }

    /** What to ask for, with the phone's clock and zone now. */
    static Session.Plan plan(long now, long activitySince, long heartSince) {
        Session.Plan plan = new Session.Plan();
        plan.androidSdk = Build.VERSION.SDK_INT;
        plan.now = now;
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.Instant at = java.time.Instant.ofEpochSecond(now);
        plan.offset = zone.getRules().getOffset(at).getTotalSeconds();
        java.time.zone.ZoneOffsetTransition next = zone.getRules().nextTransition(at);
        if (next != null) {
            plan.nextChange = next.getInstant().getEpochSecond();
            plan.offsetAfter = next.getOffsetAfter().getTotalSeconds();
        }
        plan.activitySince = activitySince;
        plan.heartSince = heartSince;
        return plan;
    }

    /** The Bluetooth link to the watch: whole messages out in pieces, pieces in as whole messages. */
    private static final class Link implements Session.Link {
        private final RingManager ring;
        private final LinkedBlockingQueue<Integer> conn = new LinkedBlockingQueue<Integer>(), found = new LinkedBlockingQueue<Integer>(),
                mtus = new LinkedBlockingQueue<Integer>(), acks = new LinkedBlockingQueue<Integer>(), writes = new LinkedBlockingQueue<Integer>();
        private final LinkedBlockingQueue<byte[]> messages = new LinkedBlockingQueue<byte[]>();
        private final Frame.Assembler assembler = new Frame.Assembler();
        private volatile boolean connected;
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic channel;
        private int mtu = 23;

        Link(RingManager ring) {
            this.ring = ring;
        }

        private final BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                connected = state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS;
                conn.offer(connected ? 1 : -Math.max(1, status));
                if (!connected) writes.offer(-1);
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                found.offer(status);
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int m, int status) {
                mtus.offer(status == BluetoothGatt.GATT_SUCCESS ? m : -1);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                acks.offer(status);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                writes.offer(status);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
                piece(c, value);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT >= 33) return; // the value overload has it
                piece(c, c.getValue());
            }
        };

        private void piece(BluetoothGattCharacteristic c, byte[] v) {
            if (!CHANNEL.equals(c.getUuid()) || v == null) return;
            byte[] m;
            synchronized (assembler) {
                m = assembler.feed(v.clone());
            }
            if (m != null) messages.offer(m);
        }

        /** Connects, finds the channel, asks for the MTU and switches its notifications on; null when ready, else the sentence. */
        @SuppressWarnings("deprecation")
        String open(Context ctx, BluetoothDevice device) throws InterruptedException {
            long t0 = SystemClock.elapsedRealtime();
            ring.log("watch: connecting");
            gatt = device.connectGatt(ctx.getApplicationContext(), false, cb, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) return NOT_CONNECTED;
            Integer st = conn.poll(CONNECT_MS, TimeUnit.MILLISECONDS);
            if (st == null || st < 0) {
                ring.log("watch: " + (st == null ? "no connection within " + CONNECT_MS / 1000 + " s" : "connection failed, status " + (-st)));
                return NOT_CONNECTED;
            }
            ring.log("watch: connected in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            // fast only while it downloads; the link is closed right after
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            SystemClock.sleep(300);
            if (!gatt.discoverServices()) return NO_SERVICE;
            Integer d = found.poll(STEP_MS * 2, TimeUnit.MILLISECONDS);
            BluetoothGattService s = d != null && d == BluetoothGatt.GATT_SUCCESS ? gatt.getService(SERVICE) : null;
            channel = s == null ? null : s.getCharacteristic(CHANNEL);
            ring.log("watch: " + gatt.getServices().size() + " services, Withings channel " + (channel == null ? "missing" : "found"));
            if (channel == null) return NO_SERVICE;
            if (gatt.requestMtu(MTU)) {
                Integer m = mtus.poll(STEP_MS, TimeUnit.MILLISECONDS);
                if (m != null && m > 23) mtu = m;
            }
            ring.log("watch: MTU " + mtu);
            boolean ok = gatt.setCharacteristicNotification(channel, true);
            BluetoothGattDescriptor cccd = channel.getDescriptor(Protocol.CCCD);
            if (ok && cccd != null) {
                acks.clear();
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0;
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    started = gatt.writeDescriptor(cccd);
                }
                Integer ack = started ? acks.poll(STEP_MS, TimeUnit.MILLISECONDS) : null;
                ok = ack != null && ack == BluetoothGatt.GATT_SUCCESS;
            }
            ring.log("watch: notifications " + (ok ? "on" : "not confirmed"));
            return ok ? null : NOT_CONNECTED;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void send(byte[] msg) throws IOException, InterruptedException {
            List<byte[]> pieces = Frame.pieces(msg, mtu - 3);
            for (byte[] piece : pieces) {
                if (!connected || gatt == null) throw new IOException("link gone");
                writes.clear();
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = gatt.writeCharacteristic(channel, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
                } else {
                    channel.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    channel.setValue(piece);
                    started = gatt.writeCharacteristic(channel);
                }
                if (!started) throw new IOException("write refused");
                Integer st = writes.poll(STEP_MS, TimeUnit.MILLISECONDS);
                if (st == null || st != BluetoothGatt.GATT_SUCCESS) throw new IOException(st == null ? "write not confirmed" : "write status " + st);
            }
        }

        @Override
        public byte[] next(long ms) throws IOException, InterruptedException {
            long until = SystemClock.elapsedRealtime() + ms;
            while (true) {
                byte[] m = messages.poll(Math.min(250, Math.max(1, until - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS);
                if (m != null) return m;
                if (!connected) throw new IOException("link gone");
                if (SystemClock.elapsedRealtime() >= until) return null;
            }
        }

        void close() {
            try {
                if (gatt != null) {
                    gatt.disconnect();
                    SystemClock.sleep(200);
                    gatt.close();
                    ring.log("watch: closed" + (assembler.dropped > 0 ? ", " + assembler.dropped + " pieces dropped" : ""));
                }
            } catch (RuntimeException ignored) {
                // Bluetooth was switched off meanwhile: the link is gone already
            }
            connected = false;
            gatt = null;
        }
    }
}
