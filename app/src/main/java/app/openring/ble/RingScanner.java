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
import app.openring.core.SyncError;
import app.openring.core.analysis.Proximity;

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
     * the Oura ring with the strongest plausible signal after a short settle time ({@link Proximity#strongest}), or at
     * the end of the scan when one was heard too late to settle (issue #195).
     * Addresses in {@code exclude} are skipped. The scan's duty and its second half are chosen by the caller
     * (issue #195): {@code lowLatency} false uses Android's balanced mode, {@code widen} false keeps the filter for the
     * whole scan (the background job, where an unfiltered scan hears nothing with the screen off), and then the
     * preferred address is one of the filters, so a saved ring that only advertises its name is still heard.
     */
    public static BluetoothDevice find(final Context c, final String preferred, final Set<String> exclude, long timeoutMs,
            boolean lowLatency, boolean widen, GattLink.Log log) throws IOException, InterruptedException {
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
                String addr = r.getDevice().getAddress();
                if (!addr.equals(preferred) && !isOura(r)) return;
                if (addr.equals(saved)) heard(c, addr);
                if (exclude != null && exclude.contains(addr)) return;
                synchronized (seen) {
                    if (seen.isEmpty()) firstSeen[0] = SystemClock.elapsedRealtime();
                    ScanResult kept = seen.get(addr);
                    if (kept == null || Proximity.replaces(kept.getRssi(), r.getRssi())) seen.put(addr, r);
                }
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(lowLatency ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_BALANCED).build();
        // Filtered first (works with the screen off): Oura's company id or the Oura service.
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        filters.add(new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY_ID, new byte[0]).build());
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build());
        // the filtered only scan never widens, so the saved ring is also matched by its address, as watch() does
        if (!widen && preferred != null && BluetoothAdapter.checkBluetoothAddress(preferred))
            filters.add(new ScanFilter.Builder().setDeviceAddress(preferred).build());
        log.log("scanning for a ring" + (lowLatency ? "" : " (balanced)") + (widen ? "" : " (filtered only)"));
        waitForStart();
        start(a, s, filters, settings, cb);
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
                if (widen && !unfiltered && empty && SystemClock.elapsedRealtime() - started > timeoutMs / 2) {
                    // some rings only advertise a name; widen the scan for the second half
                    try {
                        s.stopScan(cb);
                    } catch (RuntimeException e) {
                        throw scannerFailed(e, a);
                    }
                    waitForStart();
                    start(a, s, null, settings, cb);
                    unfiltered = true;
                }
                synchronized (seen) {
                    if (preferred != null && seen.containsKey(preferred)) return seen.get(preferred).getDevice();
                    if (!seen.isEmpty() && SystemClock.elapsedRealtime() - firstSeen[0] > 2500) return strongest(seen);
                }
            }
            // a ring first heard shortly before the end (likely with a short scan, issue #195): the strongest heard so far
            synchronized (seen) {
                if (preferred != null && seen.containsKey(preferred)) return seen.get(preferred).getDevice();
                if (!seen.isEmpty()) return strongest(seen);
            }
        } finally {
            try {
                s.stopScan(cb);
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    /** The ring with the strongest real signal: an impossible value (127, "not available") never wins (issue #105). */
    private static BluetoothDevice strongest(Map<String, ScanResult> seen) {
        List<ScanResult> all = new ArrayList<ScanResult>(seen.values());
        int[] rssi = new int[all.size()];
        for (int i = 0; i < rssi.length; i++) rssi[i] = all.get(i).getRssi();
        return all.get(Proximity.strongest(rssi)).getDevice();
    }

    /** Starts a scan; Bluetooth switched off since the check gives the same error as the check (issue #273). */
    private static void start(BluetoothAdapter a, BluetoothLeScanner s, List<ScanFilter> filters, ScanSettings settings, ScanCallback cb) throws IOException {
        try {
            s.startScan(filters, settings, cb);
        } catch (RuntimeException e) {
            throw scannerFailed(e, a);
        }
    }

    /**
     * "Bluetooth is off" as an IOException for the sync's own handling when the adapter really is off now (review of
     * #277), any other exception unchanged.
     */
    private static IOException scannerFailed(RuntimeException e, BluetoothAdapter a) {
        String t = SyncError.scannerError(e, on(a));
        if (t == null) throw e;
        return new IOException(t, e);
    }

    /**
     * Whether the adapter is on. An adapter that cannot say counts as on, as in RingManager.bluetoothOn(): "Bluetooth is
     * off" is only said when the phone really reports it, otherwise the scanner's exception stays as it is (review of #277).
     */
    private static boolean on(BluetoothAdapter a) {
        try {
            return a != null && a.isEnabled();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static long heardSaved;

    /** Scan starts of this app in the last 30 s: Android silently blocks the sixth one (issue #74). */
    private static final java.util.ArrayDeque<Long> starts = new java.util.ArrayDeque<Long>();
    static final int MAX_STARTS = 4;
    static final long START_WINDOW_MS = 30000;

    /** Milliseconds until another scan may start, 0 when one may start now. */
    public static synchronized long startWaitMs() {
        long now = SystemClock.elapsedRealtime();
        while (!starts.isEmpty() && now - starts.peekFirst() > START_WINDOW_MS) starts.pollFirst();
        return starts.size() < MAX_STARTS ? 0 : START_WINDOW_MS - (now - starts.peekFirst()) + 200;
    }

    /** Takes one start from the budget, or returns how long to wait first. */
    static synchronized long reserveStart() {
        long w = startWaitMs();
        if (w == 0) starts.addLast(SystemClock.elapsedRealtime());
        return w;
    }

    /** Waits (on a background thread) until a scan may start, and takes it. */
    private static void waitForStart() throws InterruptedException {
        long w;
        while ((w = reserveStart()) > 0) Thread.sleep(w);
    }

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

    /** What a scan for any device hears (the Watch check, issue #251); called on a Bluetooth thread. */
    public interface Any {
        void onFound(ScanResult r);

        /** Android refused or stopped the scan (error code from {@code ScanCallback}). */
        void onScanFailed(int code);
    }

    /**
     * Starts one low latency scan without a filter, for the Watch check (issue #251): a watch's advertisement is not
     * known for sure yet, so every device is reported and the screen sorts them. The start counts in the same budget
     * as the ring's scans; null when Bluetooth is off, the permission is gone or the budget is used up ({@link #startWaitMs} says when to try
     * again). Only while the screen is on: Android delivers nothing to an unfiltered scan with the screen off.
     */
    public static Watch scanAll(Context c, final Any l) {
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) return null;
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) return null;
        if (reserveStart() > 0) return null;
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanFailed(int errorCode) {
                l.onScanFailed(errorCode);
            }

            @Override
            public void onScanResult(int type, ScanResult r) {
                l.onFound(r);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) l.onFound(r);
            }
        };
        try {
            s.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        } catch (SecurityException e) {
            // the Bluetooth permission taken back meanwhile (review of #270): null, the screen says the scan could not start
            return null;
        } catch (IllegalStateException e) {
            // Bluetooth switched off since the check (issue #273), or another scanner fault: null, as promised above, not a
            // closed screen. The screen says the scan could not start, and its next try names Bluetooth off itself.
            return null;
        }
        return new Watch(s, cb);
    }

    /**
     * Starts one continuous low latency scan for the ring with {@code address} (issue #74). It also
     * reports other Oura rings, so the finder can say when it hears a ring that is not the saved
     * one. Returns null when Bluetooth is off or has no scanner. Android blocks an app that starts
     * more than 5 scans in 30 seconds, so the caller keeps one scan running while its screen is open;
     * starts are counted together with {@link #find}'s, and a start that would exceed the budget does
     * not happen (null; {@link #startWaitMs} says when to try again).
     */
    public static Watch watch(final Context c, final String address, final Heard l) {
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) return null;
        BluetoothLeScanner s = a.getBluetoothLeScanner();
        if (s == null) return null;
        if (reserveStart() > 0) return null;
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
