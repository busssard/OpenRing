package app.openring.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.openring.core.Protocol;

/**
 * Finds an Oura ring. A reset ring advertises without a name and with a rotating private address,
 * so matching is by Oura's company id (0x02B2) in the manufacturer data, the Oura service UUID,
 * or a name containing "Oura".
 */
@SuppressLint("MissingPermission")
public final class RingScanner {
    private RingScanner() {}

    public static BluetoothAdapter adapter(Context c) {
        BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    public static boolean isOura(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        String name = r.getDevice().getName();
        if (rec != null) {
            if (rec.getManufacturerSpecificData(Protocol.COMPANY_ID) != null) return true;
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) for (ParcelUuid u : uuids) if (Protocol.SERVICE.equals(u.getUuid())) return true;
            if (name == null) name = rec.getDeviceName();
        }
        return name != null && name.toLowerCase().contains("oura");
    }

    /**
     * Scan up to {@code timeoutMs}. Returns the preferred address as soon as it is seen, otherwise
     * the strongest Oura ring after a short settle time. Addresses in {@code exclude} are skipped.
     */
    public static BluetoothDevice find(final Context c, final String preferred, final Set<String> exclude, long timeoutMs, GattLink.Log log)
            throws IOException, InterruptedException {
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) throw new IOException("Bluetooth is off");
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) throw new IOException("Bluetooth LE scanner unavailable");
        final String saved = app.openring.data.Prefs.of(c).address();
        final Map<String, ScanResult> seen = new HashMap<String, ScanResult>();
        final long[] firstSeen = {0};
        final int[] failed = {0};
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanFailed(int errorCode) {
                failed[0] = errorCode == 0 ? -1 : errorCode;
            }

            @Override
            public void onScanResult(int type, ScanResult r) {
                if (!isOura(r)) return;
                String addr = r.getDevice().getAddress();
                if (addr.equals(saved)) heard(c, addr);
                if (exclude != null && exclude.contains(addr)) return;
                synchronized (seen) {
                    if (seen.isEmpty()) firstSeen[0] = SystemClock.elapsedRealtime();
                    seen.put(addr, r);
                }
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        // Filtered first (works with the screen off): Oura's company id or the Oura service.
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        filters.add(new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY_ID, new byte[0]).build());
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build());
        log.log("scanning for a ring");
        s.startScan(filters, settings, cb);
        boolean unfiltered = false;
        long started = SystemClock.elapsedRealtime();
        try {
            long deadline = started + timeoutMs;
            while (SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(250);
                if (failed[0] != 0) throw new IOException("Bluetooth scan failed (error " + failed[0] + ")");
                boolean empty;
                synchronized (seen) {
                    empty = seen.isEmpty();
                }
                if (!unfiltered && empty && SystemClock.elapsedRealtime() - started > timeoutMs / 2) {
                    // some rings only advertise a name; widen the scan for the second half
                    s.stopScan(cb);
                    s.startScan(null, settings, cb);
                    unfiltered = true;
                }
                synchronized (seen) {
                    if (preferred != null && seen.containsKey(preferred)) return seen.get(preferred).getDevice();
                    if (!seen.isEmpty() && SystemClock.elapsedRealtime() - firstSeen[0] > 2500) {
                        ScanResult best = null;
                        for (ScanResult r : seen.values()) if (best == null || r.getRssi() > best.getRssi()) best = r;
                        return best.getDevice();
                    }
                }
            }
        } finally {
            try {
                s.stopScan(cb);
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static long heardSaved;

    /** Notes that this phone heard the saved ring now (for the ring finder, issue #74), at most every 10 s. */
    static void heard(Context c, String addr) {
        long now = System.currentTimeMillis() / 1000;
        synchronized (RingScanner.class) {
            if (now - heardSaved < 10) return;
            heardSaved = now;
        }
        if (addr.equals(app.openring.data.Prefs.of(c).address())) app.openring.data.Prefs.of(c).setLastHeard(now);
    }

    /** What a continuous scan hears; called on a Bluetooth thread. */
    public interface Heard {
        /** One advertisement: {@code ours} when it is the saved ring, otherwise another Oura ring. */
        void onHeard(boolean ours, int rssi, long elapsedMs);

        /** Android refused or stopped the scan (error code from {@code ScanCallback}). */
        void onScanFailed(int code);
    }

    /** A running continuous scan; stop it when the screen pauses. */
    public static final class Watch {
        private final BluetoothLeScanner scanner;
        private final ScanCallback cb;
        private boolean stopped;

        Watch(BluetoothLeScanner scanner, ScanCallback cb) {
            this.scanner = scanner;
            this.cb = cb;
        }

        public synchronized void stop() {
            if (stopped) return;
            stopped = true;
            try {
                scanner.stopScan(cb);
            } catch (RuntimeException ignored) {
                // Bluetooth was switched off meanwhile: the scan is gone already
            }
        }
    }

    /**
     * Starts one continuous low latency scan for the ring with {@code address} (issue #74). It also
     * reports other Oura rings, so the finder can say when it hears a ring that is not the saved
     * one. Returns null when Bluetooth is off or has no scanner. Android blocks an app that starts
     * more than 5 scans in 30 seconds, so the caller keeps one scan running while its screen is open.
     */
    public static Watch watch(final Context c, final String address, final Heard l) {
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) return null;
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) return null;
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanFailed(int errorCode) {
                l.onScanFailed(errorCode);
            }

            @Override
            public void onScanResult(int type, ScanResult r) {
                String addr = r.getDevice().getAddress();
                boolean ours = addr.equals(address);
                if (!ours && !isOura(r)) return;
                if (ours) heard(c, addr);
                l.onHeard(ours, r.getRssi(), SystemClock.elapsedRealtime());
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) onScanResult(0, r);
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        filters.add(new ScanFilter.Builder().setDeviceAddress(address).build());
        filters.add(new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY_ID, new byte[0]).build());
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build());
        s.startScan(filters, settings, cb);
        return new Watch(s, cb);
    }
}
