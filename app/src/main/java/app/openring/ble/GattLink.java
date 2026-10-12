package app.openring.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.openring.core.Protocol;
import app.openring.core.RingClient;

/**
 * Blocking GATT link to the ring. Android's callback API is turned into a simple pipe: every
 * notification lands in one queue, and writes are strictly sequential (Android refuses overlapping
 * GATT operations). Handles the two things that bite on Android: the MTU must be raised (the ring
 * uses ~203 byte frames) and a freshly reset ring refuses notifications until the link is bonded.
 */
@SuppressLint("MissingPermission")
public final class GattLink implements RingClient.Link {
    public interface Log {
        void log(String msg);
    }

    private final Context ctx;
    private final BluetoothDevice device;
    private final Log log;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar, notifyChar;

    private final LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<byte[]>();
    /** Frames from the ring's other notify channels (98ed0004 and up), kept apart from replies. */
    private final LinkedBlockingQueue<byte[]> side = new LinkedBlockingQueue<byte[]>();
    private final java.util.Map<java.util.UUID, Integer> sideCounts = new java.util.HashMap<java.util.UUID, Integer>();
    private final LinkedBlockingQueue<Integer> writeAcks = new LinkedBlockingQueue<Integer>();
    private final LinkedBlockingQueue<Integer> descAcks = new LinkedBlockingQueue<Integer>();
    private final LinkedBlockingQueue<Integer> connState = new LinkedBlockingQueue<Integer>();
    private final LinkedBlockingQueue<Integer> mtuAcks = new LinkedBlockingQueue<Integer>();
    private final LinkedBlockingQueue<Integer> discovered = new LinkedBlockingQueue<Integer>();
    private volatile boolean connected;
    private volatile int mtu = 23;

    private GattLink(Context ctx, BluetoothDevice device, Log log) {
        this.ctx = ctx.getApplicationContext();
        this.device = device;
        this.log = log;
    }

    public BluetoothDevice device() {
        return device;
    }

    public boolean isConnected() {
        return connected;
    }

    public int mtu() {
        return mtu;
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            connected = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS;
            connState.offer(connected ? 1 : -Math.max(1, status));
            if (!connected) inbox.offer(new byte[0]); // wake any waiting reader
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
            mtuAcks.offer(status);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            discovered.offer(status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            writeAcks.offer(status);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            descAcks.offer(status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            route(c, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return; // handled by the value overload
            route(c, c.getValue());
        }
    };

    private void route(BluetoothGattCharacteristic c, byte[] v) {
        if (v == null) return;
        if (Protocol.NOTIFY.equals(c.getUuid())) {
            inbox.offer(v.clone());
            return;
        }
        synchronized (sideCounts) {
            Integer n = sideCounts.get(c.getUuid());
            sideCounts.put(c.getUuid(), n == null ? 1 : n + 1);
            if (n == null || n < 10) log.log("frame on " + c.getUuid().toString().substring(0, 8) + ": " + app.openring.core.Hex.encode(v));
        }
        side.offer(v.clone());
    }

    /** Next frame from the extra channels, or null. */
    public byte[] pollSide() {
        return side.poll();
    }

    public String sideSummary() {
        synchronized (sideCounts) {
            return sideCounts.isEmpty() ? "no frames on other channels" : sideCounts.toString();
        }
    }

    /** Connect, raise the MTU, discover services and subscribe (bonding if the ring asks). */
    public static GattLink open(Context ctx, BluetoothDevice device, Log log, long timeoutMs) throws IOException, InterruptedException {
        GattLink l = new GattLink(ctx, device, log);
        try {
            l.connect(timeoutMs);
            return l;
        } catch (IOException e) {
            l.close();
            throw e;
        } catch (InterruptedException e) {
            l.close();
            throw e;
        }
    }

    private void connect(long timeoutMs) throws IOException, InterruptedException {
        log.log("connecting to " + device.getAddress());
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("Bluetooth refused to open a GATT connection");
        waitConnected(timeoutMs);
        // The official app bonds the ring, and a provisioned ring drops unencrypted links after a
        // few connection events, so bond first when this phone is not bonded yet.
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            log.log("not bonded yet; requesting a bond (accept the pairing prompt if it appears)");
            try {
                ensureBond();
                log.log("bonded");
            } catch (IOException e) {
                log.log("bonding did not complete: " + e.getMessage());
            }
            if (!connected) {
                log.log("link dropped while bonding; reconnecting");
                gatt.close();
                connState.clear();
                gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) throw new IOException("Bluetooth refused to open a GATT connection");
                waitConnected(timeoutMs);
            }
        }
        log.log("connected, requesting MTU");
        SystemClock.sleep(300);
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        for (int attempt = 0; attempt < 2 && mtu < 100; attempt++) {
            if (gatt.requestMtu(247)) {
                Integer m = mtuAcks.poll(6, TimeUnit.SECONDS);
                log.log("MTU " + mtu + (m == null ? " (no reply)" : ""));
            } else {
                SystemClock.sleep(500);
            }
        }
        // history frames use the ring's ~203 byte MTU and are not reassembled across notifications
        if (mtu < 100) throw new IOException("the phone could not raise the Bluetooth MTU (" + mtu + "), data would be cut off");
        if (!gatt.discoverServices()) throw new IOException("service discovery could not start");
        Integer d = discovered.poll(20, TimeUnit.SECONDS);
        if (d == null || d != BluetoothGatt.GATT_SUCCESS) throw new IOException("service discovery failed");
        BluetoothGattService svc = gatt.getService(Protocol.SERVICE);
        if (svc == null) throw new IOException("this device has no Oura ring service");
        writeChar = svc.getCharacteristic(Protocol.WRITE);
        notifyChar = svc.getCharacteristic(Protocol.NOTIFY);
        if (writeChar == null || notifyChar == null) throw new IOException("ring characteristics missing");
        if (!subscribe()) {
            log.log("ring wants an encrypted link; bonding");
            ensureBond();
            if (!subscribe()) throw new IOException("could not subscribe to ring notifications even after bonding");
        }
        log.log("subscribed");
        subscribeOthers(svc);
    }

    /**
     * Also listen on every other notify or indicate channel the ring exposes. A Ring 4 has
     * 98ed0004..6 (BrightOura's GATT dump); live streams may arrive there instead of on 0003.
     */
    @SuppressWarnings("deprecation")
    private void subscribeOthers(BluetoothGattService svc) throws InterruptedException {
        StringBuilder found = new StringBuilder();
        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                if (Protocol.NOTIFY.equals(ch.getUuid())) continue;
                int props = ch.getProperties();
                boolean notify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean indicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                if (!notify && !indicate) continue;
                // standard services (battery, device info, service changed) are not the ring's data
                String u = ch.getUuid().toString();
                if (u.startsWith("00002a")) continue;
                gatt.setCharacteristicNotification(ch, true);
                BluetoothGattDescriptor d = ch.getDescriptor(Protocol.CCCD);
                boolean ok = false;
                if (d != null) {
                    byte[] val = notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    descAcks.clear();
                    boolean started;
                    if (Build.VERSION.SDK_INT >= 33) {
                        started = gatt.writeDescriptor(d, val) == 0;
                    } else {
                        d.setValue(val);
                        started = gatt.writeDescriptor(d);
                    }
                    Integer st = started ? descAcks.poll(5, TimeUnit.SECONDS) : null;
                    ok = st != null && st == BluetoothGatt.GATT_SUCCESS;
                }
                if (found.length() > 0) found.append(", ");
                found.append(u.substring(0, 8)).append(notify ? " notify" : " indicate").append(ok ? " on" : " refused");
            }
        }
        log.log("other channels: " + (found.length() == 0 ? "none" : found));
    }

    private void waitConnected(long timeoutMs) throws IOException, InterruptedException {
        Integer st = connState.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (st == null) throw new IOException("ring did not accept the connection in time");
        if (st < 0) throw new IOException("connection failed (GATT status " + (-st) + ")");
    }

    @SuppressWarnings("deprecation")
    private boolean subscribe() throws InterruptedException {
        gatt.setCharacteristicNotification(notifyChar, true);
        BluetoothGattDescriptor cccd = notifyChar.getDescriptor(Protocol.CCCD);
        if (cccd == null) return true;
        descAcks.clear();
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0;
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            started = gatt.writeDescriptor(cccd);
        }
        if (!started) return false;
        // the system pairing consent can appear here, so give the user time to accept it
        Integer st = descAcks.poll(45, TimeUnit.SECONDS);
        return st != null && st == BluetoothGatt.GATT_SUCCESS;
    }

    private void ensureBond() throws IOException, InterruptedException {
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) return;
        final LinkedBlockingQueue<Integer> bond = new LinkedBlockingQueue<Integer>();
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && d.getAddress().equals(device.getAddress())) {
                    bond.offer(i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1));
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(r, f);
        try {
            if (device.getBondState() == BluetoothDevice.BOND_NONE && !device.createBond()) {
                throw new IOException("Android refused to start bonding");
            }
            long deadline = SystemClock.elapsedRealtime() + 90_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                Integer s = bond.poll(1, TimeUnit.SECONDS);
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) return;
                if (s != null && s == BluetoothDevice.BOND_NONE) throw new IOException("bonding was declined or failed");
            }
            throw new IOException("bonding timed out (accept the pairing request when it appears)");
        } finally {
            try {
                ctx.unregisterReceiver(r);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public synchronized void write(byte[] data) throws IOException {
        if (!connected) throw new IOException("ring disconnected");
        writeAcks.clear();
        boolean started = false;
        for (int attempt = 0; attempt < 5 && !started; attempt++) {
            if (Build.VERSION.SDK_INT >= 33) {
                started = gatt.writeCharacteristic(writeChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
            } else {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                writeChar.setValue(data);
                started = gatt.writeCharacteristic(writeChar);
            }
            if (!started) SystemClock.sleep(60);
        }
        if (!started) throw new IOException("Bluetooth stack refused the write");
        try {
            Integer st = writeAcks.poll(6, TimeUnit.SECONDS);
            if (st == null) throw new IOException("write was never acknowledged");
            if (st != BluetoothGatt.GATT_SUCCESS) throw new IOException("write failed (GATT status " + st + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    @Override
    public byte[] poll(long timeoutMs) throws InterruptedException, IOException {
        byte[] f = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (f != null && f.length == 0) {
            if (!connected) throw new IOException("ring disconnected");
            return null;
        }
        if (f == null && !connected) throw new IOException("ring disconnected");
        return f;
    }

    @Override
    public void clearInbox() {
        inbox.clear();
    }

    public void close() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                SystemClock.sleep(200);
                gatt.close();
            }
        } catch (RuntimeException ignored) {
        }
        connected = false;
        gatt = null;
    }
}
