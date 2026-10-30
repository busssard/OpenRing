package app.openring.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import app.openring.core.Auth;
import app.openring.core.Hex;
import app.openring.core.HistoryBatch;
import app.openring.core.Protocol;
import app.openring.core.RingClient;
import app.openring.core.RingEvent;
import app.openring.core.SyncError;
import app.openring.core.Texts;
import app.openring.data.Analyzer;
import app.openring.data.Db;
import app.openring.data.Prefs;

/**
 * Everything that talks to the ring: sync, pairing, live heart rate, feature switches and the
 * factory reset. Operations are serialised (the ring has a single BLE link). The flow follows
 * open_health's RingSession: authenticate, Ring 5 stream setup, time sync, sleep analysis
 * trigger, then the checkpointed history drain with cursor rebase on reboot.
 */
@SuppressLint("MissingPermission")
public final class RingManager {
    public interface Listener {
        void onRingStatus();
    }

    public interface Callback {
        void done(boolean ok, String message);
    }

    public interface LiveListener {
        void onBeat(int bpm, int ibiMs);

        void onLiveState(String state);

        /** Other live values: "spo2" (%), "spo2_hr" (bpm), "temp" (sensor C). */
        void onValue(String kind, double value);

        /**
         * Measurements the ring stored in the last seconds, oldest first: "ibi" (clean beat to beat
         * intervals in ms, the only source of live HRV), "hr" (always on heart rate in bpm, about
         * 1.92 s apart; an average, so heart rate only, issue #152) or "spo2" (one percent per second).
         */
        void onSeries(String kind, int[] values);
    }

    /** Live accelerometer samples for game mode. */
    public interface MotionListener {
        void onSamples(List<int[]> xyz);

        void onMotionState(String state, boolean streaming);
    }

    /** Sees every batch a download stores (used by live mode to read fresh samples). */
    public interface EventObserver {
        void onEvents(List<RingEvent> events);
    }

    private static RingManager instance;

    public static synchronized RingManager get(Context c) {
        if (instance == null) instance = new RingManager(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ReentrantLock op = new ReentrantLock();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final LinkedList<String> logLines = new LinkedList<String>();
    private volatile String status = "";
    private volatile boolean busy;
    /** Syncs running, download or analysis (issue #21). */
    private final java.util.concurrent.atomic.AtomicInteger syncs = new java.util.concurrent.atomic.AtomicInteger();
    /** Syncs past their download, analysing; they no longer hold the ring (issue #21). */
    private final java.util.concurrent.atomic.AtomicInteger analysing = new java.util.concurrent.atomic.AtomicInteger();
    private volatile double progress = -1;
    /** Live session tokens and the blood oxygen choice (issue #153). */
    private final app.openring.core.LiveSessions live = new app.openring.core.LiveSessions();
    private volatile RingClient current;
    /** The sync whose download holds the ring now; a stop for a sync reaches only that one (issue #43). */
    private final app.openring.core.RingTasks.Owner downloader = new app.openring.core.RingTasks.Owner();
    /** Guards {@link #syncs} when a sync ends and {@link #endLeft}, so the last sync shows the final line (issue #43). */
    private final Object syncEnd = new Object();
    /** The final line of a sync that ended while another one still ran; the last one shows it if it has none. */
    private String endLeft;
    private volatile boolean cancelRequested;

    private RingManager(Context c) {
        ctx = c;
    }

    // ------------------------------------------------------------------- status bus

    public void addListener(Listener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public String status() {
        return status;
    }

    /** A ring task runs, or a sync is still analysing what it downloaded (the ring is free then). */
    public boolean busy() {
        return busy || syncs.get() > 0;
    }

    /** This app holds a Bluetooth connection to the ring right now (the ring then stops advertising). */
    public boolean linked() {
        return current != null;
    }

    /** Sync progress 0..1, or -1 when unknown. */
    public double progress() {
        return progress;
    }

    public List<String> logLines() {
        synchronized (logLines) {
            return new ArrayList<String>(logLines);
        }
    }

    private void notifyListeners() {
        main.post(new Runnable() {
            @Override
            public void run() {
                List<Listener> copy;
                synchronized (listeners) {
                    copy = new ArrayList<Listener>(listeners);
                }
                for (Listener l : copy) l.onRingStatus();
            }
        });
    }

    public void log(String msg) {
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        synchronized (logLines) {
            logLines.add(f.format(new Date()) + "  " + msg);
            while (logLines.size() > 600) logLines.removeFirst();
        }
        android.util.Log.i("OpenRing", msg);
        notifyListeners();
    }

    /**
     * Status line of a ring task. While a sync analyses (the ring is free then, issue #21) the
     * line keeps saying "Analysing" and other tasks only write to the log.
     */
    private void setStatus(String s) {
        if (analysing.get() > 0) {
            log(s);
            return;
        }
        forceStatus(s);
    }

    /** Status line of the sync itself, also while it analyses. */
    private void forceStatus(String s) {
        status = s;
        log(s);
    }

    private final GattLink.Log glog = new GattLink.Log() {
        @Override
        public void log(String msg) {
            RingManager.this.log(msg);
        }
    };

    // --------------------------------------------------------------------- linking

    private static final class Session {
        GattLink link;
        RingClient client;
        boolean scanned;
    }

    private Session open(boolean anyRing, Set<String> exclude) throws IOException, InterruptedException {
        BluetoothAdapter a = RingScanner.adapter(ctx);
        if (a == null) throw new IOException("This phone has no Bluetooth");
        if (!a.isEnabled()) throw new IOException(SyncError.BLUETOOTH_OFF_TEXT);
        Prefs p = Prefs.of(ctx);
        String addr = p.address();
        Session s = new Session();
        if (!anyRing && addr != null && (exclude == null || !exclude.contains(addr))) {
            BluetoothDevice d = a.getRemoteDevice(addr);
            setStatus("Connecting to your ring");
            for (int attempt = 1; attempt <= 2 && s.link == null; attempt++) {
                try {
                    s.link = GattLink.open(ctx, d, glog, 12000);
                } catch (IOException e) {
                    log("direct connect attempt " + attempt + " failed (" + e.getMessage() + ")");
                    if (attempt == 1) Thread.sleep(1500);
                }
            }
            if (s.link == null) log("scanning instead");
        }
        if (s.link == null) {
            setStatus("Looking for your ring");
            BluetoothDevice d = RingScanner.find(ctx, anyRing ? null : addr, exclude, 40000, glog);
            if (d == null) {
                throw new IOException("No ring found. Put it on its charger next to the phone, and close the Oura app if it is installed.");
            }
            setStatus("Connecting to your ring");
            s.link = GattLink.open(ctx, d, glog, 20000);
            s.scanned = true;
        }
        Prefs.of(ctx).setLastHeard(System.currentTimeMillis() / 1000); // for the ring finder (issue #74)
        s.client = new RingClient(s.link);
        s.client.setLog(new RingClient.Log() {
            @Override
            public void log(String msg) {
                RingManager.this.log(msg);
            }
        });
        current = s.client;
        return s;
    }

    private void close(Session s) {
        current = null;
        if (s != null && s.link != null) {
            Prefs.of(ctx).setLastHeard(System.currentTimeMillis() / 1000);
            s.link.close();
        }
    }

    /** One sync, so a stop can name it (issue #43). */
    public static final class SyncTicket {
        volatile boolean stop;
    }

    /**
     * Stop the sync of {@code t} (the background job when Android ends it). It reaches the ring only
     * while that sync's download holds it; once the download is over (the ring is then free for Live,
     * games and other syncs, issue #21) nothing else is touched (issue #43).
     */
    public void cancelSync(SyncTicket t) {
        if (t == null) return;
        t.stop = true;
        if (downloader.stopIf(t, new Runnable() {
            @Override
            public void run() {
                cancelRequested = true;
                RingClient c = current;
                if (c != null) c.cancel();
            }
        })) log("sync stopped by the system");
    }

    /**
     * Runs {@code r} with the ring lock held, so no sync or ring task can start meanwhile, and only when
     * no sync is under way (download, screen log or analysis). False, and {@code r} does not run, when
     * the ring is busy. Used by Delete all local data (issue #43).
     */
    public boolean whileRingFree(Runnable r) {
        if (syncs.get() > 0 || !op.tryLock()) return false;
        try {
            // a sync counted while the lock was taken still waits for it; leave the ring to it
            if (syncs.get() > 0) return false;
            busy = true;
            notifyListeners();
            r.run();
            return true;
        } finally {
            busy = false;
            op.unlock();
            notifyListeners();
        }
    }

    // ------------------------------------------------------------------------- sync

    public static final class SyncResult {
        public boolean ok;
        public String message;
        public int events;
    }

    /** Full sync plus analysis. Blocks; call from a background thread. */
    public SyncResult syncBlocking() {
        return syncBlocking(new SyncTicket());
    }

    /** As {@link #syncBlocking()}; {@link #cancelSync} with the same ticket stops this sync only. */
    public SyncResult syncBlocking(SyncTicket ticket) {
        SyncResult res = new SyncResult();
        Prefs prefs = Prefs.of(ctx);
        if (prefs.demo()) {
            res.message = "Demo mode: syncing is off";
            return res;
        }
        byte[] key = prefs.loadKey();
        if (key == null) {
            res.message = "No ring key saved. Finish setup first.";
            return res;
        }
        // counted from here, also while it waits for the ring, so busy() sees it (issue #43). busy()
        // stays true until the analysis has ended, also when the download fails with an Error, so
        // screens reload only once the new nights are there (issue #21)
        syncs.incrementAndGet();
        boolean analysingRaised = false;
        String own = null;
        try {
            Db held;
            while (true) {
                // a long analysis replaces the results (issue #230): wait for it without the ring, every write of the
                // download would wait for it with the ring held
                if (!waitForLongRun(ticket)) {
                    res.message = "Sync stopped";
                    log("sync stopped while it waited for the analysis");
                    return res;
                }
                // a game or live session that just ended may still be closing its link
                boolean locked;
                try {
                    locked = op.tryLock(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    locked = false;
                }
                if (!locked) {
                    res.message = "The ring is busy with another task";
                    log("sync skipped: the ring is busy with another task");
                    return res;
                }
                held = Db.get(ctx);
                if (held.holdForRing()) break;
                // a long analysis began meanwhile: give the ring back and wait for it
                op.unlock();
            }
            boolean stopped = false;
            try {
                downloader.take(ticket);
                cancelRequested = ticket.stop;
                busy = true;
                progress = -1;
                prefs.setLastAttempt(System.currentTimeMillis() / 1000);
                notifyListeners();
                download(res, key, prefs);
            } finally {
                try {
                    prefs.setLastSyncError(SyncError.stored(res.ok, res.message));
                    if (res.ok) prefs.setLastSync(System.currentTimeMillis() / 1000);
                } finally {
                    // the download is over and the link closed: free the ring for Live, games and
                    // Settings while the screen log is read and the nights are analysed (issue #21).
                    // From now on a stop for this sync reaches nothing (issue #43)
                    downloader.release(ticket);
                    stopped = cancelRequested || ticket.stop;
                    // the screens get a plain sentence, the raw text stays in the log and the data check (issue #47)
                    own = res.ok ? "Up to date" : app.openring.core.SyncError.plain(res.message);
                    analysing.incrementAndGet();
                    analysingRaised = true;
                    progress = -1;
                    busy = false;
                    held.releaseForRing();
                    op.unlock();
                }
            }
            try {
                // screen time (issue #13): copy the phone's screen log before the nights are analysed
                app.openring.data.ScreenUse.collect(ctx, true);
            } catch (RuntimeException e) {
                log("screen time not read: " + e);
            }
            try {
                if (Db.get(ctx).eventCount() > 0 && !stopped) {
                    forceStatus("Analysing");
                    Analyzer.run(ctx, new Analyzer.Log() {
                        @Override
                        public void log(String m) {
                            RingManager.this.log(m);
                        }
                    });
                }
            } catch (Throwable e) {
                // also out of memory: the analysis rolled back and stored its error (issue #230), the sync goes on
                log("analysis failed: " + android.util.Log.getStackTraceString(e));
            }
        } finally {
            if (analysingRaised) analysing.decrementAndGet();
            if (own != null && !res.ok) log("sync failed: " + res.message);
            String show;
            synchronized (syncEnd) {
                int left = syncs.decrementAndGet();
                // a second sync still under way shows its own line when it ends, not this one's "Up to date" (issue #43)
                show = app.openring.core.RingTasks.syncEndStatus(left, own, endLeft);
                endLeft = left > 0 ? (own != null ? own : endLeft) : null;
            }
            if (show != null) forceStatus(show);
            else if (own != null) log("sync ended (" + own + "), another sync is still under way");
        }
        return res;
    }

    /**
     * Waits while a long analysis replaces the stored results (issue #230), with the status line saying why; false
     * when the sync was stopped meanwhile. Holds nothing, so Settings and the screens stay usable.
     */
    private boolean waitForLongRun(SyncTicket ticket) {
        boolean said = false;
        while (Db.get(ctx).longRun()) {
            if (ticket.stop) return false;
            if (!said) {
                setStatus(SyncError.SYNC_WAITS);
                notifyListeners();
                said = true;
            }
            try {
                Db.get(ctx).awaitLongRun(1000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !ticket.stop;
    }

    /** The connect and download attempts of a sync; fills {@code res}. Runs with the ring lock held. */
    private void download(SyncResult res, byte[] key, Prefs prefs) {
        Set<String> exclude = new HashSet<String>();
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                if (cancelRequested) {
                    res.message = "Sync stopped";
                    break;
                }
                Session s = null;
                try {
                    s = open(false, exclude);
                    res.events += syncSession(s, key, prefs);
                    res.ok = true;
                    res.message = "Sync complete";
                    break;
                } catch (RingClient.AuthException e) {
                    if (s != null && s.scanned && e.state == Auth.WRONG_KEY && exclude.size() < 2) {
                        exclude.add(s.link.device().getAddress());
                        log("a different ring answered first; scanning again");
                        attempt--;
                        continue;
                    }
                    res.message = SyncError.raw(e);
                    break;
                } catch (IOException e) {
                    String raw = SyncError.raw(e);
                    log("attempt " + attempt + " failed: " + raw);
                    boolean on = bluetoothOn();
                    // Bluetooth turned off during the sync: say so, not the disconnect it caused
                    res.message = SyncError.attemptError(raw, on);
                    // only Bluetooth really off, no ring found, a key problem or a stop end the sync at once;
                    // passing Bluetooth errors (a refused write, a failed scan) get the other attempts (issue #157)
                    if (!SyncError.retryable(res.message, on)) break;
                    if (attempt < 3) Thread.sleep(2500);
                } catch (HistoryBatch.BatchException e) {
                    String raw = SyncError.raw(e);
                    log("attempt " + attempt + " failed: " + raw);
                    boolean on = bluetoothOn();
                    res.message = SyncError.attemptError(raw, on);
                    if (!on) break;
                    if (attempt < 3) Thread.sleep(2500);
                } finally {
                    close(s);
                }
            }
        } catch (InterruptedException e) {
            res.message = "Interrupted";
        } catch (RuntimeException e) {
            res.message = "Unexpected error: " + e;
            log(android.util.Log.getStackTraceString(e));
        }
    }

    /** The phone's Bluetooth is on right now (an unreadable state counts as on, so the sync keeps its attempts). */
    private boolean bluetoothOn() {
        try {
            BluetoothAdapter a = RingScanner.adapter(ctx);
            return a != null && a.isEnabled();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private int syncSession(Session s, byte[] key, final Prefs prefs) throws IOException, InterruptedException, HistoryBatch.BatchException {
        RingClient c = s.client;
        final Db db = Db.get(ctx);
        RingClient.DeviceInfo info = null;
        try {
            info = c.firmware();
        } catch (IOException e) {
            log("firmware read failed: " + e.getMessage());
        }
        String serial = c.serial();
        String hw = c.hardwareId();
        if (prefs.serial() != null && !prefs.serial().equals(serial)) log("note: this ring's serial differs from the saved one");
        setStatus("Checking your key");
        c.authenticate(key);
        prefs.setAddress(s.link.device().getAddress());
        prefs.setSerial(serial);
        if (hw != null) prefs.setHardware(hw);
        if (info != null) prefs.setFirmware(info.firmware);
        c.setupAppStream(hw);

        // async notification flags, as the official app, Gadgetbridge and OpenStrap send them
        try {
            c.requestTag(Protocol.setNotification(0x3f), 0x1d);
        } catch (IOException e) {
            log("notification flags not accepted: " + e.getMessage());
        }
        long now = System.currentTimeMillis() / 1000;
        int tzHalf = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1800000;
        long ringNow = c.syncTime(now, tzHalf);
        if (db.count("SELECT COUNT(*) FROM events WHERE tag = 66") == 0) {
            // no time anchor yet: the forced form, which a measuring ring may not skip (OpenStrap)
            try {
                c.requestTag(Protocol.syncTimeForced(now, tzHalf), 0x13);
            } catch (IOException e) {
                log("forced time sync not accepted: " + e.getMessage());
            }
        }
        log("ring time reply " + ringNow);
        c.checkSleepAnalysis(false);
        try {
            int[] b = c.battery();
            prefs.setBattery(b[0], now);
            db.addBattery(now, b[0]);
        } catch (IOException e) {
            log("battery read failed: " + e.getMessage());
        }

        return drainNew(c, false, null);
    }

    /**
     * Download everything the ring recorded since the saved cursor and store it. Rebases the
     * cursor when the ring rebooted. {@code quiet} keeps the sync status line untouched (used
     * before a live measurement).
     */
    private int drainNew(RingClient c, final boolean quiet, final EventObserver observer) throws IOException, InterruptedException, HistoryBatch.BatchException {
        final Db db = Db.get(ctx);
        long now = System.currentTimeMillis() / 1000;
        long epoch = db.kvLong("epoch", 1);
        long cursor = db.kvLong("cursor", 0);
        final long[] minDs = {Long.MAX_VALUE};
        final long[] inserted = {0};
        final long epochF[] = {epoch};
        final long captured = now;
        RingClient.BatchSink sink = new RingClient.BatchSink() {
            long firstLeft = -1;
            boolean anchored;

            @Override
            public boolean onBatch(List<RingEvent> events, long next, long left, int total) {
                try {
                    inserted[0] += db.insertBatch(events, epochF[0], captured);
                    if (observer != null) observer.onEvents(events);
                    for (RingEvent e : events) minDs[0] = Math.min(minDs[0], e.ds);
                    db.put("cursor", next);
                    if (left == 0 && !anchored && next > 0 && total > 0) {
                        // drained up to the present: the newest event happened about now. This is
                        // the time anchor of last resort, and it is saved even if a later step fails.
                        db.insertPhoneAnchor(epochF[0], next - 1, System.currentTimeMillis() / 1000);
                        anchored = true;
                    }
                } catch (RuntimeException e) {
                    log("storage error: " + e);
                    return false;
                }
                if (quiet) return true;
                if (firstLeft < 0 && left > 0) firstLeft = left;
                progress = firstLeft > 0 ? Math.max(0, Math.min(1, 1 - left / (double) firstLeft)) : -1;
                status = left > 0 ? String.format(Locale.US, "Syncing, %s, %.0f KB left", Texts.count(total, "event", "events"), left / 1024.0)
                        : "Syncing, " + Texts.count(total, "event", "events");
                notifyListeners();
                return true;
            }
        };
        if (!quiet) setStatus("Syncing");
        RingClient.DrainResult r;
        try {
            r = c.drain(cursor, sink);
        } catch (HistoryBatch.BatchException e) {
            if (cursor > 0 && e.resultCode == 0xff) {
                log("ring rejected the saved cursor; starting a new boot epoch");
                epochF[0] = ++epoch;
                db.put("epoch", epoch);
                db.put("cursor", 0);
                cursor = 0;
                r = c.drain(0, sink);
            } else {
                throw e;
            }
        }
        if (r.eventsSynced == 0 && cursor > 0 && !c.cursorMarkerPresent(cursor)) {
            log("saved cursor is not on the ring any more (reboot or reset); rebasing");
            epochF[0] = ++epoch;
            db.put("epoch", epoch);
            db.put("cursor", 0);
            r = c.drain(0, sink);
        }
        log(String.format(Locale.US, "synced %d events (%d new), cursor %d", r.eventsSynced, inserted[0], r.nextCursor));
        return r.eventsSynced;
    }

    // ---------------------------------------------------------------------- pairing

    /**
     * Adopt a ring. With {@code existingKey} the key is verified against the ring (the official
     * app's key, or a key you backed up). Without it, a fresh key is generated and installed, which
     * only works on a factory reset ring.
     */
    public void pair(final byte[] existingKey, final boolean install, final Callback cb) {
        runOp(SyncError.Task.PAIR, cb, new Op() {
            @Override
            public String run() throws Exception {
                Prefs prefs = Prefs.of(ctx);
                // a sync may still analyse (it released the ring, issue #21). Wait for it before the
                // link opens, so the link cannot go stale meanwhile, and hold the analysis lock for
                // the few seconds of pairing: no analysis runs between the wipe of a different ring's
                // history and the new serial, in the same order as before
                synchronized (Analyzer.class) {
                    Session s = open(true, null);
                    try {
                        String serial = s.client.serial();
                        byte[] key = existingKey != null ? existingKey : Auth.newKey();
                        if (install) {
                            setStatus("Installing the key");
                            int st = s.client.setAuthKey(key);
                            if (st != 0) {
                                throw new IOException(st == 1 ? "The ring already has a key. Factory reset it first, or use the existing key."
                                        : st == 5 ? "The ring reports unfinished factory tests (status 5)."
                                        : String.format(Locale.US, "The ring refused the key (status 0x%02x)", st));
                            }
                        }
                        setStatus("Verifying the key");
                        s.client.authenticate(key);
                        prefs.saveKey(key);
                        prefs.setAddress(s.link.device().getAddress());
                        if (prefs.serial() != null && !prefs.serial().equals(serial)) {
                            // a different ring: start a fresh history
                            Db.get(ctx).resetAll();
                        }
                        prefs.setSerial(serial);
                        try {
                            RingClient.DeviceInfo info = s.client.firmware();
                            prefs.setFirmware(info.firmware);
                        } catch (IOException ignored) {
                        }
                        if (install) {
                            // a ring keyed outside the official app starts with daytime HR and SpO2 off
                            s.client.setFeatureMode(Protocol.FEATURE_DAYTIME_HR, Protocol.MODE_AUTOMATIC);
                            s.client.setFeatureMode(Protocol.FEATURE_SPO2, Protocol.MODE_AUTOMATIC);
                            long now = System.currentTimeMillis() / 1000;
                            s.client.syncTime(now, TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1800000);
                        }
                        return "Paired with " + serial;
                    } finally {
                        close(s);
                    }
                }
            }
        });
    }

    // --------------------------------------------------------------------- features

    public static final int[] FEATURES = {Protocol.FEATURE_DAYTIME_HR, Protocol.FEATURE_SPO2, Protocol.FEATURE_RESTING_HR};

    /** Reads feature modes; message is "id:mode,id:mode". */
    public void readFeatures(final Callback cb) {
        runOp(SyncError.Task.ACTION, cb, new Op() {
            @Override
            public String run() throws Exception {
                Session s = open(false, null);
                try {
                    s.client.authenticate(Prefs.of(ctx).loadKey());
                    StringBuilder sb = new StringBuilder();
                    for (int f : FEATURES) {
                        int[] st = s.client.featureStatus(f);
                        if (sb.length() > 0) sb.append(',');
                        sb.append(f).append(':').append(st == null ? -1 : st[0]);
                    }
                    try {
                        int[] b = s.client.battery();
                        Prefs.of(ctx).setBattery(b[0], System.currentTimeMillis() / 1000);
                    } catch (IOException ignored) {
                    }
                    return sb.toString();
                } finally {
                    close(s);
                }
            }
        });
    }

    public void setFeature(final int feature, final int mode, final Callback cb) {
        runOp(SyncError.Task.ACTION, cb, new Op() {
            @Override
            public String run() throws Exception {
                Session s = open(false, null);
                try {
                    s.client.authenticate(Prefs.of(ctx).loadKey());
                    if (!s.client.setFeatureMode(feature, mode)) throw new IOException("the ring did not accept the change");
                    return "Saved on the ring";
                } finally {
                    close(s);
                }
            }
        });
    }

    // ------------------------------------------------------------------ factory reset

    public void factoryReset(final String confirmSerial, final Callback cb) {
        runOp(SyncError.Task.ACTION, cb, new Op() {
            @Override
            public String run() throws Exception {
                Prefs prefs = Prefs.of(ctx);
                // it stores the new cursor after the reset: not while a long analysis replaces the results (issue #230)
                final Db db = Db.get(ctx);
                if (!db.holdForRing()) throw new IOException(SyncError.REANALYSING);
                Session s = null;
                try {
                    s = open(false, null);
                    String serial = s.client.serial();
                    if (!serial.equalsIgnoreCase(confirmSerial.trim())) {
                        // the saved serial typed correctly means another ring answered, not a typo (issue #60)
                        if (prefs.serial() != null && prefs.serial().equalsIgnoreCase(confirmSerial.trim())) {
                            throw new IOException(SyncError.OTHER_RING_PREFIX + " (" + serial + "), not the saved " + prefs.serial());
                        }
                        throw new IOException("Refusing: the ring that answered is " + serial + ", not " + confirmSerial);
                    }
                    s.client.authenticate(prefs.loadKey());
                    setStatus("Erasing the ring");
                    s.client.factoryReset();
                    prefs.clearKey();
                    prefs.setSetupDone(false);
                    db.put("cursor", 0);
                    db.put("epoch", db.kvLong("epoch", 1) + 1);
                    log(serial + " was reset");
                    return Texts.RING_RESET_DONE; // a toast, Settings closes right after
                } finally {
                    close(s);
                    db.releaseForRing();
                }
            }
        });
    }

    // ---------------------------------------------------------------------- live HR

    private volatile LiveListener liveListener;

    public boolean liveSpo2() {
        return live.spo2();
    }

    /**
     * Start (or rejoin) a live heart rate session. A second call while one runs only swaps the
     * screen that receives the values, so repeated taps do not reconnect again and again.
     */
    public void startLive(LiveListener l) {
        startLive(l, false);
    }

    /**
     * @param spo2 also run the ring's blood oxygen measurement (experimental, uses more battery).
     *             Changing it while a session runs restarts the session.
     */
    public synchronized void startLive(final LiveListener l, final boolean spo2) {
        liveListener = l;
        // rejoin only a session that is still the current one: a stopped one may still be cleaning up
        // (a turned phone, Done and back within seconds), and would end without values (issue #153).
        // LiveSessions.start applies RingTasks.rejoinLive and gives 0 for a rejoin.
        final int token = live.start(spo2);
        if (token == 0) {
            post("Measuring, keep your hand still");
            return;
        }
        // a new session: the new token ends an older one at its next check; this task runs on the same
        // worker after it, once it has cleaned up and given the ring back. A blood oxygen switch too.
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (live.stale(token)) return;
                boolean locked;
                try {
                    locked = op.tryLock(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    locked = false;
                }
                if (!locked) {
                    post(token, Texts.LIVE_BUSY);
                    live.ended(token);
                    return;
                }
                // Live stores what it drains: not while a long analysis replaces the results (issue #230)
                final Db held = Db.get(ctx);
                if (!held.holdForRing()) {
                    op.unlock();
                    log("live: not started, a long analysis runs");
                    post(token, SyncError.plain(SyncError.REANALYSING, SyncError.Task.LIVE));
                    live.ended(token);
                    return;
                }
                busy = true;
                cancelRequested = false;
                Session s = null;
                boolean realtimeOn = false;
                int spo2PrevMode = -1;
                try {
                    post(token, "Connecting to your ring");
                    s = open(false, null);
                    s.client.authenticate(Prefs.of(ctx).loadKey());
                    // fresh data first: it tells whether the ring is on a finger right now
                    post(token, "Checking the ring");
                    try {
                        drainNew(s.client, true, null);
                    } catch (Exception e) {
                        log("live: quick download failed: " + e.getMessage());
                    }
                    try {
                        Db.get(ctx).rebuildTime(System.currentTimeMillis() / 1000);
                    } catch (RuntimeException e) {
                        log("live: dating failed: " + e);
                    }
                    app.openring.data.RightNow now = app.openring.data.RightNow.load(ctx);
                    log("live: ring sensor " + now.skinTemp + " C, worn " + now.worn);
                    if (now.worn == 0) {
                        post(token, String.format(Locale.US, "The ring reads %.0f \u00b0C, room temperature, so it is probably not on a finger. "
                                + "Put it on (not on the charger) and keep your hand still.", now.skinTemp));
                    }
                    int[] fs = s.client.featureStatus(Protocol.FEATURE_DAYTIME_HR);
                    log("live: daytime HR feature " + (fs == null ? "status unknown" : "mode " + fs[0] + " status " + fs[1] + " state " + fs[2]));
                    if (fs != null && fs[0] == Protocol.MODE_OFF) {
                        s.client.setFeatureMode(Protocol.FEATURE_DAYTIME_HR, Protocol.MODE_AUTOMATIC);
                        log("live: daytime HR was off on the ring, switched it on");
                    }
                    boolean modeOk = s.client.setFeatureMode(Protocol.FEATURE_DAYTIME_HR, Protocol.MODE_CONNECTED_LIVE);
                    log("live: switch to live mode " + (modeOk ? "accepted" : "NOT accepted"));
                    if (spo2) {
                        int[] ss = s.client.featureStatus(Protocol.FEATURE_SPO2);
                        spo2PrevMode = ss == null ? Protocol.MODE_AUTOMATIC : ss[0];
                        boolean ok = s.client.setFeatureMode(Protocol.FEATURE_SPO2, Protocol.MODE_CONNECTED_LIVE);
                        log("live: SpO2 was mode " + spo2PrevMode + ", live mode " + (ok ? "accepted" : "NOT accepted"));
                        if (!ok) {
                            ok = s.client.setFeatureMode(Protocol.FEATURE_SPO2, Protocol.MODE_REQUESTED);
                            log("live: SpO2 requested mode " + (ok ? "accepted" : "NOT accepted"));
                        }
                    }
                    if (now.worn != 0) post(token, spo2 ? "Measuring, keep your hand completely still. Blood oxygen needs 30 to 60 seconds of calm."
                            : "Measuring, keep your hand still. The first beats take 10 to 30 seconds.");
                    long started = System.currentTimeMillis(), lastBeat = 0, lastLatest = started;
                    long lastSpo2Poll = started, lastRefresh = started;
                    int spo2Counter = -1;
                    // real beats and always on heart rate stay apart: only real beats give HRV (issue #152)
                    final app.openring.core.analysis.LiveFeed feed = new app.openring.core.analysis.LiveFeed();
                    EventObserver watch = new EventObserver() {
                        @Override
                        public void onEvents(List<RingEvent> events) {
                            feed.addAll(events);
                        }
                    };
                    long lastArm = started;
                    boolean subscribed = false;
                    int rawLogged = 0, beats = 0, frames = 0, stored = 0;
                    int lastStatus = -1;
                    RingClient.Terminal latestDone = new RingClient.Terminal() {
                        @Override
                        public boolean done(app.openring.core.Packet p) {
                            return p.extTag() == 0x25;
                        }
                    };
                    while (live.isCurrent(token) && System.currentTimeMillis() - started < 30 * 60 * 1000) {
                        long nowMs = System.currentTimeMillis();
                        List<app.openring.core.Packet> packets = new ArrayList<app.openring.core.Packet>();
                        byte[] f = s.link.poll(500);
                        if (f != null) {
                            frames++;
                            if (rawLogged < 60) {
                                log("live raw " + Hex.encode(f));
                                rawLogged++;
                            }
                            packets.addAll(app.openring.core.Packet.parseMany(f));
                        }
                        for (byte[] sf; (sf = s.link.pollSide()) != null; ) {
                            frames++;
                            packets.addAll(app.openring.core.Packet.parseMany(sf));
                        }
                        // the ring drops back to normal mode after about 48 s (DHR_mode:3 -> 1): re-arm
                        if (nowMs - lastArm > 30000) {
                            lastArm = nowMs;
                            s.client.request(Protocol.setFeatureMode(Protocol.FEATURE_DAYTIME_HR, Protocol.MODE_CONNECTED_LIVE), null, 600, false);
                            if (spo2) s.client.request(Protocol.setFeatureMode(Protocol.FEATURE_SPO2, Protocol.MODE_REQUESTED), null, 600, false);
                        }
                        long quietFor = nowMs - Math.max(started, lastBeat);
                        // no beats after 10 s: ask for the feature's data too (official app style)
                        if (beats == 0 && !subscribed && nowMs - started > 10000) {
                            subscribed = true;
                            List<app.openring.core.Packet> r = s.client.request(
                                    Protocol.setFeatureSubscription(Protocol.FEATURE_DAYTIME_HR, 0x07), new RingClient.Terminal() {
                                        @Override
                                        public boolean done(app.openring.core.Packet p) {
                                            return p.extTag() == 0x27;
                                        }
                                    }, 1500, false);
                            log("live: subscription reply " + r);
                            packets.addAll(r);
                        }
                        // still nothing after 20 s: the ring's on demand measurement (real time 0x06)
                        if (beats == 0 && !realtimeOn && nowMs - started > 20000) {
                            realtimeOn = true;
                            List<app.openring.core.Packet> r = s.client.request(Protocol.realtime(Protocol.REALTIME_ON_DEMAND, 2), null, 1500, false);
                            log("live: on demand measurement reply " + r);
                            packets.addAll(r);
                        }
                        // blood oxygen: the ring's latest SpO2 reading, every 10 s (open_oura: data[3] SpO2, data[4] HR)
                        if (spo2 && nowMs - lastSpo2Poll > 10000) {
                            lastSpo2Poll = nowMs;
                            List<app.openring.core.Packet> r = s.client.request(Protocol.featureLatest(Protocol.FEATURE_SPO2), latestDone, 1500, false);
                            for (app.openring.core.Packet p : r) {
                                if (p.extTag() != 0x25 || p.u8(1) != Protocol.FEATURE_SPO2) {
                                    packets.add(p);
                                    continue;
                                }
                                if (rawLogged < 60) {
                                    log("live spo2 latest " + Hex.encode(p.payload));
                                    rawLogged++;
                                }
                                if (p.payload.length >= 12) {
                                    int counter = p.u8(5) | (p.u8(6) << 8);
                                    int sp = p.u8(10), hr = p.u8(11);
                                    if (sp >= 70 && sp <= 100 && counter != spo2Counter) value("spo2", sp);
                                    if (app.openring.core.EventCodec.plausibleHr(hr) && counter != spo2Counter) value("spo2_hr", hr);
                                    spo2Counter = counter;
                                }
                            }
                        }
                        // every 4 s: what the ring just stored. On rings that keep live readings in their
                        // history instead of streaming them (Ring 4, fw 2.6), this is the live view.
                        if (nowMs - lastRefresh > 4000) {
                            lastRefresh = nowMs;
                            try {
                                feed.clear();
                                drainNew(s.client, true, watch);
                                if (!Double.isNaN(feed.temp)) value("temp", feed.temp);
                                if (!feed.beats.isEmpty()) series("ibi", feed.beats);
                                if (!feed.heartRates.isEmpty()) series("hr", feed.heartRates);
                                int got = feed.beats.size() + feed.heartRates.size();
                                if (got > 0) {
                                    beats += got;
                                    lastBeat = nowMs;
                                    if (stored++ == 0) post(token, "Measuring. Readings arrive in small batches every few seconds.");
                                }
                                if (!feed.spo2.isEmpty()) series("spo2", feed.spo2);
                            } catch (Exception e) {
                                log("live: refresh failed: " + e.getMessage());
                            }
                        }
                        // keep showing the ring's stored latest value while waiting
                        if (quietFor > 12000 && nowMs - lastLatest > 15000) {
                            lastLatest = nowMs;
                            List<app.openring.core.Packet> r = s.client.request(Protocol.featureLatest(Protocol.FEATURE_DAYTIME_HR), latestDone, 1500, false);
                            for (app.openring.core.Packet p : r) {
                                if (p.extTag() != 0x25) {
                                    packets.add(p);
                                    continue;
                                }
                                if (rawLogged < 60) {
                                    log("live latest " + Hex.encode(p.payload));
                                    rawLogged++;
                                }
                                if (p.payload.length >= 9) {
                                    int ibi = (p.u8(7) | (p.u8(8) << 8)) & 0x0fff;
                                    if (app.openring.core.EventCodec.plausibleIbi(ibi)) beat((int) Math.round(60000.0 / ibi), 0);
                                }
                            }
                        }
                        for (app.openring.core.Packet p : packets) {
                            RingClient.LiveIndication li = RingClient.parseLive(p);
                            if (li == null) continue;
                            if (li.feature != Protocol.FEATURE_DAYTIME_HR) {
                                if (rawLogged < 60) {
                                    log("live indication feature " + li.feature + ": " + Hex.encode(p.payload));
                                    rawLogged++;
                                }
                                continue;
                            }
                            if (li.status != lastStatus) {
                                lastStatus = li.status;
                                post(token, "Ring: " + RingClient.statusText(li.status));
                            }
                            if (li.bpm > 0 && live.isCurrent(token)) {
                                beats++;
                                lastBeat = nowMs;
                                beat(li.bpm, li.ibi);
                            }
                        }
                        if (beats == 0 && frames == 0 && nowMs - started > 45000 && nowMs - started < 46100) {
                            post(token, now.worn == 0 ? "No pulse: the ring does not seem to be on a finger."
                                    : "The ring has not sent a pulse yet. Wear it snugly on a warm finger, rest your hand on a table and wait.");
                        }
                    }
                    log("live: " + beats + " beats, " + frames + " live frames, " + s.link.sideSummary());
                    post(token, beats > 0 ? "Stopped" : "Stopped without a pulse. Diagnostics, Log tab, shows what the ring sent.");
                } catch (Exception e) {
                    String raw = e.getMessage() == null ? e.toString() : e.getMessage();
                    log("live: stopped by " + raw);
                    post(token, "Live heart rate stopped. " + SyncError.plain(raw, SyncError.Task.LIVE));
                } finally {
                    try {
                        if (s != null && s.link.isConnected()) {
                            if (realtimeOn) s.client.request(Protocol.realtimeOff(), null, 500);
                            s.client.setFeatureMode(Protocol.FEATURE_DAYTIME_HR, Protocol.MODE_AUTOMATIC);
                            // leave blood oxygen as it was: live SpO2 drains the battery
                            if (spo2PrevMode >= 0) s.client.setFeatureMode(Protocol.FEATURE_SPO2, spo2PrevMode);
                        }
                    } catch (Exception ignored) {
                    }
                    close(s);
                    // only this session's own mark: a newer one may be queued already (issue #153)
                    live.ended(token);
                    busy = false;
                    held.releaseForRing();
                    op.unlock();
                    notifyListeners();
                }
            }
        });
    }

    /** Stop the live session, but only if {@code l} is the screen that owns it. */
    public synchronized void stopLive(LiveListener l) {
        if (l != null && l != liveListener) return;
        liveListener = null;
        live.stop();
    }

    private void beat(final int bpm, final int ibi) {
        main.post(new Runnable() {
            @Override
            public void run() {
                LiveListener l = liveListener;
                if (l != null) l.onBeat(bpm, ibi);
            }
        });
    }

    // ------------------------------------------------------------------ motion (games)

    private volatile MotionListener motionListener;
    private final java.util.concurrent.atomic.AtomicInteger motionToken = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Stream the ring's accelerometer (about 50 samples a second) until {@link #stopMotion}.
     * Each call starts a fresh session; an older one ends first (the worker runs one task at a
     * time). If the ring sends no motion within {@link #MOTION_GIVE_UP_MS} the session ends by
     * itself so the ring is free again for syncs and live measurements.
     */
    public void startMotion(MotionListener l) {
        motionListener = l;
        final int token = motionToken.incrementAndGet();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (token != motionToken.get()) return;
                // a sync may be running: wait for it instead of giving up straight away
                boolean locked = false;
                try {
                    long waitUntil = System.currentTimeMillis() + 3 * 60 * 1000;
                    boolean told = false;
                    while (token == motionToken.get() && System.currentTimeMillis() < waitUntil) {
                        if (op.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            locked = true;
                            break;
                        }
                        if (!told) {
                            told = true;
                            motionState("Waiting for the sync to finish", false);
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                }
                if (!locked) {
                    if (token == motionToken.get()) motionState("The ring is busy, touch steering works meanwhile", false);
                    return;
                }
                if (token != motionToken.get()) {
                    op.unlock();
                    return;
                }
                busy = true;
                notifyListeners();
                Session s = null;
                try {
                    motionState("Connecting to your ring", false);
                    s = open(false, null);
                    s.client.authenticate(Prefs.of(ctx).loadKey());
                    long lastStart = 0;
                    int frames = 0, logged = 0;
                    long started = System.currentTimeMillis();
                    while (token == motionToken.get()) {
                        long now = System.currentTimeMillis();
                        // the stream is time boxed in minutes on the ring; renew it before it runs out
                        if (now - lastStart > 4 * 60 * 1000) {
                            lastStart = now;
                            List<app.openring.core.Packet> r = s.client.request(Protocol.realtime(Protocol.REALTIME_ACM, 5), null, 800, false);
                            log("motion: stream request reply " + r);
                        }
                        byte[] f = s.link.pollSide();
                        if (f == null) f = s.link.poll(100);
                        if (f == null) {
                            if (frames == 0 && now - started > MOTION_GIVE_UP_MS) {
                                log("motion: " + s.link.sideSummary());
                                motionState(SyncError.GAME_NO_MOTION, false);
                                break;
                            }
                            if (!s.link.isConnected()) throw new IOException("the ring disconnected");
                            continue;
                        }
                        final List<int[]> xyz = app.openring.core.Tilt.parseFrame(f);
                        if (xyz.isEmpty()) {
                            if (logged++ < 20) log("motion: other frame " + Hex.encode(f));
                            continue;
                        }
                        if (frames++ == 0) motionState("Streaming", true);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                MotionListener ml = motionListener;
                                if (ml != null) ml.onSamples(xyz);
                            }
                        });
                    }
                    log("motion: " + frames + " frames in " + (System.currentTimeMillis() - started) / 1000 + " s");
                } catch (Exception e) {
                    String raw = e.getMessage() == null ? e.toString() : e.getMessage();
                    log("motion: stopped by " + raw);
                    motionState(SyncError.game(raw), false);
                } finally {
                    try {
                        if (s != null && s.link.isConnected()) s.client.request(Protocol.realtimeOff(), null, 500);
                    } catch (Exception ignored) {
                    }
                    close(s);
                    busy = false;
                    op.unlock();
                    notifyListeners();
                }
            }
        });
    }

    static final long MOTION_GIVE_UP_MS = 15000;

    /** End the motion session of {@code l} (or any session when {@code l} is null). */
    public void stopMotion(MotionListener l) {
        if (l != null && motionListener != null && l != motionListener) return;
        motionListener = null;
        motionToken.incrementAndGet();
    }

    private void motionState(final String state, final boolean streaming) {
        log("motion: " + state);
        main.post(new Runnable() {
            @Override
            public void run() {
                MotionListener ml = motionListener;
                if (ml != null) ml.onMotionState(state, streaming);
            }
        });
    }

    private void series(final String kind, List<Integer> list) {
        final int[] v = new int[list.size()];
        for (int i = 0; i < v.length; i++) v[i] = list.get(i);
        main.post(new Runnable() {
            @Override
            public void run() {
                LiveListener l = liveListener;
                if (l != null) l.onSeries(kind, v);
            }
        });
    }

    private void value(final String kind, final double v) {
        main.post(new Runnable() {
            @Override
            public void run() {
                LiveListener l = liveListener;
                if (l != null) l.onValue(kind, v);
            }
        });
    }

    /**
     * A message of the live session {@code token}. One that is no longer current (stopped, and a newer
     * one may be starting for the same screen) only writes to the log, so its "Stopped" cannot cover
     * the new session (issue #153).
     */
    private void post(int token, String state) {
        if (!live.isCurrent(token)) {
            log("live (ended session): " + state);
            return;
        }
        post(state);
    }

    private void post(final String state) {
        log("live: " + state);
        main.post(new Runnable() {
            @Override
            public void run() {
                LiveListener l = liveListener;
                if (l != null) l.onLiveState(state);
            }
        });
    }


    // ------------------------------------------------------------------------ plumbing

    private interface Op {
        String run() throws Exception;
    }

    /**
     * Runs a ring task of Setup or Settings. A failure reaches the callback and the status line as a plain
     * sentence; the raw text goes to the Bluetooth log (issue #60).
     */
    private void runOp(final SyncError.Task task, final Callback cb, final Op o) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String msg;
                if (!op.tryLock()) {
                    log("not started: the ring is busy with another task");
                    reply(cb, false, SyncError.plain("The ring is busy with another task", task));
                    return;
                }
                cancelRequested = false;
                busy = true;
                notifyListeners();
                try {
                    msg = o.run();
                    ok = true;
                } catch (Exception e) {
                    String raw = e.getMessage() == null ? e.toString() : e.getMessage();
                    log("failed: " + raw);
                    msg = SyncError.plain(raw, task);
                } finally {
                    busy = false;
                    op.unlock();
                }
                // a sync analysing meanwhile keeps its "Analysing" (issue #21)
                if (analysing.get() == 0) status = ok ? "" : msg;
                reply(cb, ok, msg);
                notifyListeners();
            }
        });
    }

    private void reply(final Callback cb, final boolean ok, final String msg) {
        if (cb == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.done(ok, msg);
            }
        });
    }

    public static String keyHex(byte[] key) {
        return key == null ? "" : Hex.encode(key);
    }
}
