package app.openring.ui;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.openring.ble.RingManager;
import app.openring.ble.RingScanner;
import app.openring.ble.WatchLink;
import app.openring.core.PermissionAsk;
import app.openring.core.RingGate;
import app.openring.core.WatchCheck;
import app.openring.data.Prefs;

/**
 * The Watch check (issue #251, phase 1a): scan, pick a watch, one short look at its Bluetooth layout, a report to copy.
 * No pairing, nothing stored; the report lives in this screen only. The ring always wins: the check runs only when it
 * can take the ring lock at once ({@link RingGate#takeIfFree}) and holds it for the session (at most 30 s), so the
 * phone never keeps two links. Scan and session stop in {@link #onPause}. Demo mode never touches Bluetooth: it shows
 * {@link WatchCheck#demoFound} and {@link WatchCheck#demoReport} through the same screen code.
 */
public final class WatchActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean demo, resumed, askedPermission, permissionBlocked, quietDenial;
    private LinearLayout listBox, reportCard, issueBox;
    private TextView scanStatus, issueText, issueButton, scanButton, reportStatus, reportText;
    private RingScanner.Watch scan;
    private long scanUntil;
    /** What the scan heard, by address; written on a Bluetooth thread. */
    private final Map<String, WatchCheck.Found> found = new HashMap<String, WatchCheck.Found>();
    private volatile boolean stopped;
    private Thread session;
    /** The last report: in memory only, gone with the screen. */
    private WatchCheck.Report report;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        demo = Prefs.of(this).demo();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        scroll.addView(root);

        LinearLayout top = Ui.hbox(this);
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(app.openring.R.drawable.ic_chevron_left);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        int bp = Ui.dp(this, 8);
        back.setPadding(bp, bp, bp, bp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.h1(this, "Watch check"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));

        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.body(this, WatchCheck.INTRO));
        if (demo) {
            intro.addView(Ui.gap(this, 8));
            intro.addView(Ui.text(this, WatchCheck.DEMO, 13, Ui.WARN, Ui.REGULAR));
        }
        root.addView(intro);

        // ---- the scan ----
        LinearLayout scanCard = Ui.card(this);
        scanCard.addView(Ui.caption(this, "Nearby"));
        scanCard.addView(Ui.gap(this, 6));
        scanStatus = Ui.text(this, "", 14, Ui.TEXT, Ui.REGULAR);
        scanCard.addView(scanStatus);
        scanCard.addView(Ui.text(this, WatchCheck.SIGNAL, 12, Ui.TEXT_3, Ui.REGULAR));
        issueBox = Ui.vbox(this);
        issueText = Ui.text(this, "", 13, Ui.WARN, Ui.REGULAR);
        issueBox.addView(issueText);
        issueButton = Ui.button(this, "Allow", Ui.OXY, true);
        LinearLayout.LayoutParams il = Ui.matchWrap();
        il.setMargins(0, Ui.dp(this, 8), 0, 0);
        issueBox.addView(issueButton, il);
        issueBox.setVisibility(View.GONE);
        scanCard.addView(issueBox, Ui.matchWrap());
        listBox = Ui.vbox(this);
        scanCard.addView(listBox, Ui.matchWrap());
        scanButton = Ui.smallButton(this, "Scan again", Ui.OXY);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        LinearLayout.LayoutParams sl = Ui.matchWrap();
        sl.setMargins(0, Ui.dp(this, 10), 0, 0);
        scanCard.addView(scanButton, sl);
        root.addView(scanCard);

        // ---- the report ----
        reportCard = Ui.card(this);
        reportCard.addView(Ui.caption(this, "Report"));
        reportCard.addView(Ui.gap(this, 6));
        reportStatus = Ui.text(this, "", 14, Ui.TEXT, Ui.REGULAR);
        reportCard.addView(reportStatus);
        reportCard.addView(Ui.gap(this, 8));
        reportText = Ui.text(this, "", 11, Ui.TEXT_2, android.graphics.Typeface.MONOSPACE);
        reportText.setTextIsSelectable(true);
        reportCard.addView(reportText);
        reportCard.addView(Ui.gap(this, 8));
        reportCard.addView(Ui.text(this, WatchCheck.REPORT_NOTE, 12, Ui.TEXT_3, Ui.REGULAR));
        TextView copy = Ui.button(this, "Copy the report", Ui.SLEEP, true);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(false);
            }
        });
        LinearLayout.LayoutParams cl = Ui.matchWrap();
        cl.setMargins(0, Ui.dp(this, 10), 0, 0);
        reportCard.addView(copy, cl);
        TextView copyBytes = Ui.smallButton(this, "Copy with message bytes", Ui.WARN);
        copyBytes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy(true);
            }
        });
        LinearLayout.LayoutParams bl = Ui.matchWrap();
        bl.setMargins(0, Ui.dp(this, 8), 0, 0);
        reportCard.addView(copyBytes, bl);
        reportCard.addView(Ui.text(this, WatchCheck.PRIVATE, 12, Ui.WARN, Ui.REGULAR));
        reportCard.addView(Ui.gap(this, 10));
        reportCard.addView(Ui.text(this, WatchCheck.SEND_BACK, 13, Ui.TEXT_2, Ui.REGULAR));
        reportCard.setVisibility(View.GONE);
        root.addView(reportCard);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (demo) {
            showDemo();
            return;
        }
        // granted in the app's settings meanwhile: forget the refusal, Android asks afresh if it takes it back (#140)
        if (Permissions.granted(this)) permissionBlocked = quietDenial = false;
        Permissions.remember(this);
        if (session == null) startScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(refresh);
        h.removeCallbacks(retry);
        stopScan();
        // the link is closed and the ring lock given back within a moment; the report so far stays
        stopped = true;
        if (session != null) session.interrupt();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // the same rule as Find my ring: after a second refusal only the app's settings can switch it on (issue #140)
        PermissionAsk.Answer a = PermissionAsk.answer(Permissions.granted(this), perms, results, Permissions.needed(),
                Permissions.rationale(this), Prefs.of(this).permissionDenied(), quietDenial);
        if (askedPermission) {
            permissionBlocked = a.blocked;
            quietDenial = a.quiet;
        }
        Permissions.store(this, a.denied);
        if (resumed && !demo) startScan();
    }

    // ------------------------------------------------------------------ scan

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (resumed) startScan();
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            showList();
            if (scan == null) return;
            if (SystemClock.elapsedRealtime() >= scanUntil) {
                stopScan();
                showList();
                return;
            }
            h.postDelayed(this, 500);
        }
    };

    void startScan() {
        if (demo || !resumed || scan != null || session != null) return;
        issueBox.setVisibility(View.GONE);
        if (!Permissions.granted(this)) {
            String what = Build.VERSION.SDK_INT >= 31 ? "the Nearby devices permission"
                    : "the Location permission (Android 11 and older ask for it to find Bluetooth devices; Open Ring has no internet and keeps no place)";
            if (permissionBlocked) {
                issue("To find the watch, this app needs " + what + ". Android no longer asks, so switch it on in the app's settings.", "Open app settings",
                        new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", getPackageName(), null)));
            } else {
                issue("To find the watch, this app needs " + what + ".", "Allow", null);
            }
            return;
        }
        if (Build.VERSION.SDK_INT < 31 && !locationOn()) {
            issue("Location is off. Android 11 and older only find Bluetooth devices while Location is on.", "Open Location settings",
                    new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        BluetoothAdapter a = RingScanner.adapter(this);
        if (a == null || !a.isEnabled()) {
            issue("Bluetooth is off. Switch it on to find the watch.", "Open Bluetooth settings", new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        long wait = RingScanner.startWaitMs();
        if (wait > 0) {
            // Android blocks the sixth scan start in 30 seconds; the ring's scans count too
            scanStatus.setText("Android allows only a few scans in a row. Scanning again in " + ((wait + 999) / 1000) + " s.");
            h.postDelayed(retry, wait);
            return;
        }
        synchronized (found) {
            found.clear();
        }
        scan = RingScanner.scanAll(this, new RingScanner.Any() {
            @Override
            public void onFound(ScanResult r) {
                heard(r);
            }

            @Override
            public void onScanFailed(final int code) {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        stopScan();
                        scanStatus.setText("Android stopped the scan (error " + code + "). Tap Scan again in a minute.");
                    }
                });
            }
        });
        if (scan == null) {
            scanStatus.setText("The scan could not start. Tap Scan again in a minute.");
            return;
        }
        scanUntil = SystemClock.elapsedRealtime() + WatchCheck.SCAN_MS;
        h.post(refresh);
    }

    void stopScan() {
        if (scan != null) scan.stop();
        scan = null;
    }

    /** One advertisement (Bluetooth thread): kept by address with its newest signal. */
    void heard(ScanResult r) {
        String addr = r.getDevice().getAddress();
        ScanRecord rec = r.getScanRecord();
        String name = rec == null ? null : rec.getDeviceName();
        if (name == null) {
            try {
                name = r.getDevice().getName();
            } catch (SecurityException e) {
                name = null;
            }
        }
        synchronized (found) {
            WatchCheck.Found f = found.get(addr);
            if (f == null) found.put(addr, f = new WatchCheck.Found(addr, name, r.getRssi()));
            f.rssi = r.getRssi();
            if (name != null) f.name = name;
            List<ParcelUuid> uuids = rec == null ? null : rec.getServiceUuids();
            if (uuids != null) for (ParcelUuid u : uuids) f.service(u.getUuid().toString());
        }
    }

    void issue(String text, String button, final Intent open) {
        issueText.setText(text);
        issueButton.setText(button);
        issueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (open == null) {
                    askedPermission = true;
                    Permissions.request(WatchActivity.this);
                    return;
                }
                try {
                    startActivity(open);
                } catch (RuntimeException e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                }
            }
        });
        issueBox.setVisibility(View.VISIBLE);
        scanStatus.setText("Not scanning");
        listBox.removeAllViews();
    }

    boolean locationOn() {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return true;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
    }

    /** The scan list, strongest first; tap a row to check that device. */
    void showList() {
        List<WatchCheck.Found> all;
        synchronized (found) {
            all = new ArrayList<WatchCheck.Found>();
            for (WatchCheck.Found f : found.values()) {
                WatchCheck.Found c = new WatchCheck.Found(f.address, f.name, f.rssi);
                for (String s : f.services) c.service(s);
                all.add(c);
            }
        }
        all = WatchCheck.strongestFirst(all);
        boolean running = scan != null;
        if (all.isEmpty()) scanStatus.setText(running ? "Scanning. Keep the watch next to the phone." : WatchCheck.NOTHING_FOUND);
        else scanStatus.setText(running ? "Scanning, tap your watch to check it." : demo ? "Made up watches nearby. Tap one to see an example report." : "Tap your watch to check it.");
        scanButton.setVisibility(running || demo || session != null ? View.GONE : View.VISIBLE);
        listBox.removeAllViews();
        for (final WatchCheck.Found f : all) {
            LinearLayout row = Ui.vbox(this);
            int pv = Ui.dp(this, 8);
            row.setPadding(0, pv, 0, pv);
            row.addView(Ui.text(this, WatchCheck.title(f), 16, WatchCheck.likelyWatch(f) ? Ui.OXY : Ui.TEXT, Ui.MEDIUM));
            row.addView(Ui.text(this, WatchCheck.detail(f), 12, Ui.TEXT_3, Ui.REGULAR));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick(f);
                }
            });
            listBox.addView(Ui.divider(this));
            listBox.addView(row, Ui.matchWrap());
        }
    }

    // ------------------------------------------------------------------ check

    void showDemo() {
        synchronized (found) {
            found.clear();
            for (WatchCheck.Found f : WatchCheck.demoFound()) found.put(f.address, f);
        }
        showList();
        if (report != null) showReport(report);
        else if (getIntent().getBooleanExtra("demo_report", false)) pick(WatchCheck.demoFound().get(1));
    }

    /** Checks the device in {@code f}: in demo mode the made up report, otherwise one short session holding the ring lock. */
    void pick(final WatchCheck.Found f) {
        if (session != null) return;
        if (demo) {
            showReport(report = WatchCheck.demoReport());
            return;
        }
        stopScan();
        h.removeCallbacks(refresh);
        BluetoothAdapter a = RingScanner.adapter(this);
        if (a == null || !a.isEnabled()) {
            startScan(); // says Bluetooth is off
            return;
        }
        final BluetoothDevice device = a.getRemoteDevice(f.address);
        final Context app = getApplicationContext();
        final RingManager ring = RingManager.get(this);
        reportCard.setVisibility(View.VISIBLE);
        reportStatus.setText("Checking " + WatchCheck.title(f) + ", this takes up to 30 seconds. " + WatchCheck.PAIR_NOTE);
        reportText.setText("");
        stopped = false;
        session = new Thread(new Runnable() {
            @Override
            public void run() {
                WatchCheck.Report r = null;
                // the ring always wins: no wait for it, and while the check holds the lock a ring task waits as for Live
                if (RingGate.RING.takeIfFree()) {
                    try {
                        r = WatchLink.check(app, device, WatchCheck.title(f), f.rssi, new app.openring.ble.GattLink.Log() {
                            @Override
                            public void log(String msg) {
                                ring.log(msg);
                            }
                        }, new WatchLink.Stop() {
                            @Override
                            public boolean stopped() {
                                return stopped;
                            }
                        });
                    } finally {
                        RingGate.RING.op.unlock();
                    }
                }
                final WatchCheck.Report done = r;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        session = null;
                        if (isFinishing() || isDestroyed()) return;
                        if (done == null) {
                            reportStatus.setText(WatchCheck.BUSY);
                            reportText.setText("");
                        } else {
                            showReport(report = done);
                        }
                        showList();
                    }
                });
            }
        }, "watch check");
        session.start();
        showList();
    }

    void showReport(WatchCheck.Report r) {
        reportCard.setVisibility(View.VISIBLE);
        if (!r.connected) reportStatus.setText(WatchCheck.NO_ANSWER);
        else if (r.problem != null) reportStatus.setText("The check ended early (" + r.problem + "). What it found so far is below.");
        else reportStatus.setText("Done. The watch was let go again.");
        reportText.setText(WatchCheck.text(r, false));
    }

    void copy(boolean bytes) {
        if (report == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText(bytes ? "Open Ring watch check, private" : "Open Ring watch check", WatchCheck.text(report, bytes));
        if (bytes && Build.VERSION.SDK_INT >= 33) {
            // Android 13 and newer then hide the copied text in their clipboard preview
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, bytes ? "Copied, with the private message bytes" : "Copied", Toast.LENGTH_SHORT).show();
    }
}
