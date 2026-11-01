package app.openring.ble;

import android.annotation.SuppressLint;
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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.openring.core.Protocol;
import app.openring.core.WatchCheck;

/**
 * One short look at a watch for the Watch check (issue #251, phase 1a). Unlike {@link GattLink} it never asks for a
 * bond, keeps Android's balanced link speed and knows nothing about the ring. It connects, lists every service and
 * characteristic, reads the standard Device Information and Battery, switches notifications on for the watch's own
 * characteristics and listens; the only writes are those notification switches. Everything ends within
 * {@link WatchCheck#SESSION_MS}, and the link is always closed. The caller holds the ring lock meanwhile.
 */
@SuppressLint("MissingPermission")
public final class WatchLink {
    /** Asked between steps; true ends the session (the screen paused). */
    public interface Stop {
        boolean stopped();
    }

    private final LinkedBlockingQueue<Integer> connState = new LinkedBlockingQueue<Integer>();
    private final LinkedBlockingQueue<Integer> discovered = new LinkedBlockingQueue<Integer>();
    private final LinkedBlockingQueue<Integer> descAcks = new LinkedBlockingQueue<Integer>();
    private final LinkedBlockingQueue<byte[]> reads = new LinkedBlockingQueue<byte[]>();
    private final Map<UUID, WatchCheck.Characteristic> listening = new HashMap<UUID, WatchCheck.Characteristic>();
    private volatile boolean connected;
    private BluetoothGatt gatt;

    private static final byte[] FAILED = new byte[0];

    private WatchLink() {}

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            connected = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS;
            connState.offer(connected ? 1 : -Math.max(1, status));
            if (!connected) reads.offer(FAILED);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            discovered.offer(status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            reads.offer(status == BluetoothGatt.GATT_SUCCESS && value != null ? value.clone() : FAILED);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT >= 33) return; // handled by the value overload
            byte[] v = c.getValue();
            reads.offer(status == BluetoothGatt.GATT_SUCCESS && v != null ? v.clone() : FAILED);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            descAcks.offer(status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            heard(c, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return;
            heard(c, c.getValue());
        }
    };

    private void heard(BluetoothGattCharacteristic c, byte[] v) {
        WatchCheck.Characteristic k;
        synchronized (listening) {
            k = listening.get(c.getUuid());
        }
        if (k != null) k.message(v);
    }

    /**
     * Runs one check on {@code device} and returns what it found; never throws for a watch that does not answer, the
     * report's {@code problem} says what happened. Logs only step names, never message bytes.
     */
    public static WatchCheck.Report check(Context ctx, BluetoothDevice device, String name, int rssi, GattLink.Log log, Stop stop) {
        WatchCheck.Report r = new WatchCheck.Report();
        r.name = name;
        r.rssi = rssi;
        WatchLink l = new WatchLink();
        long start = SystemClock.elapsedRealtime();
        long end = start + WatchCheck.SESSION_MS;
        try {
            r.bondedBefore = device.getBondState() == BluetoothDevice.BOND_BONDED;
            log.log("watch check: connecting");
            l.gatt = device.connectGatt(ctx.getApplicationContext(), false, l.cb, BluetoothDevice.TRANSPORT_LE);
            if (l.gatt == null) {
                r.problem = "Bluetooth refused to open a connection";
                return r;
            }
            Integer st = l.connState.poll(WatchCheck.CONNECT_MS, TimeUnit.MILLISECONDS);
            if (st == null || st < 0) {
                r.problem = st == null ? "no answer within " + WatchCheck.CONNECT_MS / 1000 + " s" : "connection failed (status " + (-st) + ")";
                return r;
            }
            r.connected = true;
            r.connectMs = SystemClock.elapsedRealtime() - start;
            l.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
            SystemClock.sleep(300);
            if (stop.stopped()) return ended(r, "stopped");
            if (!l.gatt.discoverServices()) return ended(r, "the layout could not be read");
            Integer d = l.discovered.poll(left(end), TimeUnit.MILLISECONDS);
            if (d == null || d != BluetoothGatt.GATT_SUCCESS) return ended(r, "the layout could not be read");
            log.log("watch check: " + l.gatt.getServices().size() + " services");
            for (BluetoothGattService s : l.gatt.getServices()) {
                WatchCheck.Service ws = r.add(s.getUuid().toString());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) ws.add(c.getUuid().toString(), c.getProperties());
            }
            l.readInfo(r, end, stop);
            if (stop.stopped()) return ended(r, "stopped");
            int on = l.listenAll(r, end, stop);
            log.log("watch check: listening on " + on + " characteristics");
            long t0 = SystemClock.elapsedRealtime();
            long until = Math.min(end, t0 + WatchCheck.LISTEN_MS);
            while (SystemClock.elapsedRealtime() < until && l.connected && !stop.stopped()) Thread.sleep(100);
            r.listenMs = SystemClock.elapsedRealtime() - t0;
            if (!l.connected) r.problem = "the watch ended the connection";
            else if (stop.stopped()) r.problem = "stopped";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (r.problem == null) r.problem = "stopped";
        } catch (RuntimeException e) {
            // Bluetooth switched off meanwhile, or a permission taken back
            if (r.problem == null) r.problem = "Bluetooth stopped (" + e.getClass().getSimpleName() + ")";
        } finally {
            l.close();
            log.log("watch check: closed" + (r.problem == null ? "" : ", " + r.problem));
        }
        return r;
    }

    private static WatchCheck.Report ended(WatchCheck.Report r, String why) {
        r.problem = why;
        return r;
    }

    private static long left(long end) {
        return Math.max(0, end - SystemClock.elapsedRealtime());
    }

    private static final UUID INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");

    private static UUID std(String id) {
        return UUID.fromString("0000" + id + "-0000-1000-8000-00805f9b34fb");
    }

    /** Model, firmware and the rest of Device Information (not the serial number), and the battery level. */
    private void readInfo(WatchCheck.Report r, long end, Stop stop) throws InterruptedException {
        BluetoothGattService info = gatt.getService(INFO);
        if (info != null) {
            r.model = readText(info, std("2a24"), end, stop);
            r.firmware = readText(info, std("2a26"), end, stop);
            r.hardware = readText(info, std("2a27"), end, stop);
            r.software = readText(info, std("2a28"), end, stop);
            r.manufacturer = readText(info, std("2a29"), end, stop);
        }
        BluetoothGattService bat = gatt.getService(BATTERY);
        if (bat != null) {
            byte[] v = read(bat.getCharacteristic(std("2a19")), end, stop);
            if (v != null && v.length >= 1) r.battery = v[0] & 0xff;
        }
    }

    private String readText(BluetoothGattService s, UUID c, long end, Stop stop) throws InterruptedException {
        byte[] v = read(s.getCharacteristic(c), end, stop);
        if (v == null) return null;
        String t = new String(v, StandardCharsets.UTF_8).trim();
        return t.isEmpty() ? null : t;
    }

    @SuppressWarnings("deprecation")
    private byte[] read(BluetoothGattCharacteristic c, long end, Stop stop) throws InterruptedException {
        if (c == null || !connected || stop.stopped() || (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) return null;
        reads.clear();
        if (!gatt.readCharacteristic(c)) return null;
        byte[] v = reads.poll(Math.min(3000, left(end)), TimeUnit.MILLISECONDS);
        return v == null || v == FAILED ? null : v;
    }

    /**
     * Switches notifications (or indications) on for every characteristic that sends and is not a standard one: the
     * standard ones are documented, and the heart rate one would switch on the sensor. Returns how many are on.
     */
    @SuppressWarnings("deprecation")
    private int listenAll(WatchCheck.Report r, long end, Stop stop) throws InterruptedException {
        int on = 0;
        for (WatchCheck.Service ws : r.services) {
            BluetoothGattService s = gatt.getService(UUID.fromString(ws.uuid));
            if (s == null) continue;
            for (WatchCheck.Characteristic wc : ws.characteristics) {
                if (!WatchCheck.sends(wc.props) || WatchCheck.standard(wc.uuid)) continue;
                if (!connected || stop.stopped() || left(end) < WatchCheck.LISTEN_MS / 2) return on;
                BluetoothGattCharacteristic c = s.getCharacteristic(UUID.fromString(wc.uuid));
                if (c == null) continue;
                synchronized (listening) {
                    listening.put(c.getUuid(), wc);
                }
                boolean ok = gatt.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(Protocol.CCCD);
                if (ok && d != null) {
                    byte[] val = (wc.props & 0x10) != 0 ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    descAcks.clear();
                    boolean started;
                    if (Build.VERSION.SDK_INT >= 33) {
                        started = gatt.writeDescriptor(d, val) == 0;
                    } else {
                        d.setValue(val);
                        started = gatt.writeDescriptor(d);
                    }
                    Integer st = started ? descAcks.poll(Math.min(3000, left(end)), TimeUnit.MILLISECONDS) : null;
                    ok = st != null && st == BluetoothGatt.GATT_SUCCESS;
                }
                wc.listening = ok;
                if (ok) on++;
            }
        }
        return on;
    }

    private void close() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                SystemClock.sleep(200);
                gatt.close();
            }
        } catch (RuntimeException ignored) {
            // Bluetooth was switched off meanwhile: the link is gone already
        }
        connected = false;
        gatt = null;
    }
}
