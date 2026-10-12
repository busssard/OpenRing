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
    public static BluetoothDevice find(Context c, final String preferred, final Set<String> exclude, long timeoutMs, GattLink.Log log)
            throws IOException, InterruptedException {
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) throw new IOException("Bluetooth is off");
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) throw new IOException("Bluetooth LE scanner unavailable");
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
}
