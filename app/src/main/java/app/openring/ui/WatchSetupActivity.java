package app.openring.ui;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.openring.ble.RingScanner;
import app.openring.ble.WatchSync;
import app.openring.core.InputLimit;
import app.openring.core.withings.Proto;
import app.openring.core.withings.Session;
import app.openring.core.withings.WatchDemo;
import app.openring.core.withings.WatchDue;
import app.openring.core.withings.WatchSetup;
import app.openring.data.Prefs;

/**
 * The watch setup (issue #251): find the watch, pair it, sign in, send the setup from the profile (showing exactly what
 * is sent) and the first sync, each step with its state and a Retry; below it the alarm and the hand calibration. The
 * sessions run through {@link WatchSync} (the ring always wins). The scan stops in {@link #onPause}; a setup or alarm
 * session runs to its end (at most 3 minutes, its result is kept), the hand calibration ends in {@link #onStop} (it
 * needs this screen). Demo mode never touches Bluetooth: a made up watch, every step done, nothing sent.
 */
public final class WatchSetupActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean demo, resumed, destroyed;
    private ScrollView scroll;
    private LinearLayout root;

    /** The setup's cards, in order. */
    static final String FIND = "find", PAIR = "pair", SIGN = "sign", SEND = "send", FIRST = "first", DONE = "done";
    static final String[] CARDS = {FIND, PAIR, SIGN, SEND, FIRST, DONE};
    static final int WAITING = 0, RUNNING = 1, OK = 2, FAILED = 3;
    private final Map<String, Integer> state = new HashMap<String, Integer>();
    private final Map<String, String> note = new HashMap<String, String>();

    // the scan
    private RingScanner.Watch scan;
    private long scanUntil;
    private int shownCount = -1;
    private boolean showAll;
    private final Map<String, Found> found = new HashMap<String, Found>();

    static final class Found {
        final String address;
        String name;
        int rssi;
        boolean withings;

        Found(String address) {
            this.address = address;
        }
    }

    // sessions
    private static volatile boolean sessionRunning;
    private String alarmNote;
    private LinkedBlockingQueue<Session.Step> hands;
    private int handIndex;
    private String handsNote;
    private BroadcastReceiver bondReceiver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        demo = Prefs.of(this).demo();
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        scroll.addView(root);
        setContentView(scroll);
        if (demo) {
            for (String k : CARDS) state.put(k, OK);
            if (getIntent().getBooleanExtra("demo_hands", false)) {
                handIndex = 1;
                handsNote = "Demo mode: the hands do not move.";
            }
        }
        render();
        final int part = getIntent().getIntExtra("part", 0);
        if (part > 0) scrollTo(part == 2 ? "hands" : "alarm", 0);
    }

    /** Scrolls to the card with {@code tag} once it is laid out (the screen test opens the alarm and the hands this way). */
    void scrollTo(final String tag, final int tries) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                View t = root.findViewWithTag(tag);
                if (t == null || t.getHeight() == 0 || t.getTop() == 0) {
                    if (tries < 20) scrollTo(tag, tries + 1);
                    return;
                }
                scroll.scrollTo(0, Math.max(0, t.getTop() - Ui.dp(WatchSetupActivity.this, 8)));
            }
        }, 150);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (demo) return;
        refreshKnown();
        if (state(FIND) != OK) startScan();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopScan();
        h.removeCallbacks(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // the hand calibration needs this screen: it ends now (calibration mode off, the phone's time sent)
        if (hands != null) hands.offer(Session.FINISH);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (bondReceiver != null) {
            try {
                unregisterReceiver(bondReceiver);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (resumed && !demo) startScan();
    }

    int state(String k) {
        Integer s = state.get(k);
        return s == null ? WAITING : s;
    }

    void set(String k, int s, String n) {
        state.put(k, s);
        if (n != null) note.put(k, n);
        else note.remove(k);
    }

    /** What the phone already knows: a saved watch is found, a bond is paired, a sent setup is done. */
    void refreshKnown() {
        Prefs p = Prefs.of(this);
        String address = p.watchAddress();
        if (address == null) return;
        if (state(FIND) != OK) set(FIND, OK, p.watchName() != null ? p.watchName() : "Saved");
        if (state(PAIR) != RUNNING) {
            boolean bonded = bonded(address);
            set(PAIR, bonded ? OK : WAITING, bonded ? "Paired with this phone" : null);
        }
        if (p.watchSetupAt() > 0 && !sessionRunning) {
            for (String k : new String[] {SIGN, SEND}) if (state(k) == WAITING) set(k, OK, null);
            if (state(FIRST) == WAITING && p.watchLastOk() > 0) set(FIRST, OK, null);
            if (state(DONE) == WAITING) set(DONE, OK, null);
        }
    }

    boolean bonded(String address) {
        try {
            BluetoothAdapter a = RingScanner.adapter(this);
            return a != null && a.getRemoteDevice(address).getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------ drawing

    void render() {
        if (destroyed) return;
        final int y = scroll.getScrollY();
        root.removeAllViews();
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
        top.addView(Ui.h1(this, "Watch setup"));
        root.addView(top);
        root.addView(Ui.gap(this, 8));
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.body(this, Explain.WATCH_SETUP));
        if (demo) {
            intro.addView(Ui.gap(this, 8));
            intro.addView(Ui.text(this, "Demo mode: a made up watch, every step shown as done. Nothing is sent.", 13, Ui.WARN, Ui.REGULAR));
        }
        root.addView(intro);
        findCard();
        pairCard();
        stepCard(SIGN, "3. Sign in", "The watch asks the app to prove it is allowed in. Open Ring answers it.", null, null);
        sendCard();
        stepCard(FIRST, "5. First sync", "Steps, distance, calories, sleep, heart rate and workouts the watch still holds, up to a month back.", null, null);
        doneCard();
        alarmCard();
        handsCard();
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, y);
            }
        });
    }

    /** A step's title with its state on the right, then its note. */
    LinearLayout header(String key, String title) {
        LinearLayout card = Ui.card(this);
        LinearLayout row = Ui.hbox(this);
        row.addView(Ui.text(this, title, 16, Ui.TEXT, Ui.MEDIUM), Ui.weight(1));
        int s = state(key);
        String label = s == OK ? "Done" : s == RUNNING ? "Running" : s == FAILED ? "Failed" : "Waiting";
        int color = s == OK ? Ui.GOOD : s == RUNNING ? Ui.OXY : s == FAILED ? Ui.WARN : Ui.TEXT_3;
        row.addView(Ui.text(this, label, 13, color, Ui.MEDIUM));
        card.addView(row);
        String n = note.get(key);
        if (n != null) {
            card.addView(Ui.gap(this, 4));
            card.addView(Ui.text(this, n, 13, s == FAILED ? Ui.WARN : Ui.TEXT_2, Ui.REGULAR));
        }
        return card;
    }

    void stepCard(String key, String title, String what, String button, final Runnable action) {
        LinearLayout card = header(key, title);
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, what, 12, Ui.TEXT_3, Ui.REGULAR));
        if (state(key) == FAILED) addButton(card, "Retry", Ui.OXY, true, new Runnable() {
            @Override
            public void run() {
                sendSetup();
            }
        });
        else if (button != null) addButton(card, button, Ui.OXY, true, action);
        root.addView(card);
    }

    void addButton(LinearLayout card, String label, int color, boolean filled, final Runnable r) {
        TextView b = filled ? Ui.button(this, label, color, true) : Ui.smallButton(this, label, color);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LinearLayout.LayoutParams l = Ui.matchWrap();
        l.setMargins(0, Ui.dp(this, 10), 0, 0);
        card.addView(b, l);
    }

    void findCard() {
        LinearLayout card = header(FIND, "1. Find the watch");
        if (demo) {
            card.addView(Ui.text(this, WatchDemo.NAME, 14, Ui.TEXT, Ui.REGULAR));
            root.addView(card);
            return;
        }
        if (state(FIND) == OK) {
            addButton(card, "Find another watch", Ui.OXY, false, new Runnable() {
                @Override
                public void run() {
                    set(FIND, WAITING, null);
                    for (String k : CARDS) if (!FIND.equals(k)) set(k, WAITING, null);
                    startScan();
                    render();
                }
            });
            root.addView(card);
            return;
        }
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, "Keep the watch next to the phone. If the Withings app still uses it, remove the watch there first.", 12, Ui.TEXT_3, Ui.REGULAR));
        List<Found> list = sorted();
        int shown = 0;
        for (final Found f : list) {
            if (!f.withings && !showAll) continue;
            shown++;
            TextView row = Ui.text(this, (f.name != null ? f.name : "No name") + "   " + f.rssi + " dBm" + (f.withings ? "" : "   (not a Withings watch)"), 15,
                    f.withings ? Ui.TEXT : Ui.TEXT_2, Ui.REGULAR);
            int pd = Ui.dp(this, 10);
            row.setPadding(0, pd, 0, pd);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick(f);
                }
            });
            card.addView(row, Ui.matchWrap());
        }
        String status = scan != null ? (shown == 0 ? "Looking for the watch" : "Tap your watch") : shown == 0 ? "No Withings watch found. Press the watch's button and scan again." : "Tap your watch";
        card.addView(Ui.text(this, status, 13, Ui.TEXT_2, Ui.REGULAR));
        if (scan == null) addButton(card, "Scan again", Ui.OXY, false, new Runnable() {
            @Override
            public void run() {
                startScan();
            }
        });
        if (!showAll) addButton(card, "Show every device nearby", Ui.TEXT_2, false, new Runnable() {
            @Override
            public void run() {
                showAll = true;
                render();
            }
        });
        root.addView(card);
    }

    List<Found> sorted() {
        List<Found> list;
        synchronized (found) {
            list = new ArrayList<Found>(found.values());
        }
        Collections.sort(list, new Comparator<Found>() {
            @Override
            public int compare(Found a, Found b) {
                if (a.withings != b.withings) return a.withings ? -1 : 1;
                return b.rssi - a.rssi;
            }
        });
        return list;
    }

    void pairCard() {
        LinearLayout card = header(PAIR, "2. Pair");
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, "Android pairs the phone with the watch once. Accept the pairing request when it shows.", 12, Ui.TEXT_3, Ui.REGULAR));
        if (!demo && state(FIND) == OK && state(PAIR) != OK && state(PAIR) != RUNNING) addButton(card, state(PAIR) == FAILED ? "Retry" : "Pair", Ui.OXY, true, new Runnable() {
            @Override
            public void run() {
                pair();
            }
        });
        root.addView(card);
    }

    void sendCard() {
        final Prefs p = Prefs.of(this);
        final WatchSetup.Profile who = demo ? demoProfile() : WatchSync.profile(this);
        LinearLayout card = header(SEND, "4. Send the setup");
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, "What is sent to the watch:", 12, Ui.TEXT_3, Ui.REGULAR));
        for (String line : WatchSetup.shown(who, java.time.ZoneId.systemDefault().getId(), p.watchAlarm())) {
            card.addView(Ui.text(this, "• " + line, 13, Ui.TEXT_2, Ui.REGULAR));
        }
        // what is missing is asked here and saved to the profile
        if (!who.complete()) {
            card.addView(Ui.gap(this, 8));
            card.addView(Ui.text(this, "The watch needs to know male or female to count calories:", 13, Ui.TEXT, Ui.REGULAR));
            LinearLayout chips = Ui.hbox(this);
            String[] names = {"Male", "Female"};
            for (int k = 0; k < 2; k++) {
                final int sex = k;
                TextView c = Ui.smallButton(this, names[k], Ui.OXY);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (demo) {
                            say("Demo mode: nothing is saved.");
                            return;
                        }
                        p.setProfile(p.age(), sex, p.weightKg(), p.heightCm(), p.targetKcal());
                        render();
                    }
                });
                LinearLayout.LayoutParams l = Ui.weight(1);
                l.setMargins(0, 0, Ui.dp(this, 6), 0);
                chips.addView(c, l);
            }
            card.addView(chips, Ui.matchWrap());
        }
        if (!p.profileSet() && !demo) profileInputs(card, p);
        // the step goal and the distance unit, the watch's own choices
        card.addView(Ui.gap(this, 8));
        card.addView(Ui.text(this, "Daily step goal (the activity hand shows how much of it you reached)", 12, Ui.TEXT_3, Ui.REGULAR));
        LinearLayout goals = Ui.hbox(this);
        for (final int g : WatchSetup.GOALS) {
            TextView c = Ui.smallButton(this, String.format(Locale.US, "%,d", g), who.goal == g ? Ui.OXY : Ui.TEXT_3);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!demo) p.setWatchGoal(g);
                    render();
                }
            });
            LinearLayout.LayoutParams l = Ui.weight(1);
            l.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 4), 0);
            goals.addView(c, l);
        }
        card.addView(goals, Ui.matchWrap());
        LinearLayout units = Ui.hbox(this);
        String[] un = {"km", "miles"};
        for (int k = 0; k < 2; k++) {
            final boolean metric = k == 0;
            TextView c = Ui.smallButton(this, un[k], who.metric == metric ? Ui.OXY : Ui.TEXT_3);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!demo) p.setWatchMetric(metric);
                    render();
                }
            });
            LinearLayout.LayoutParams l = Ui.weight(1);
            l.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 4), 0);
            units.addView(c, l);
        }
        card.addView(units, Ui.matchWrap());
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, Explain.WATCH_SETUP_SENT, 12, Ui.TEXT_3, Ui.REGULAR));
        boolean ready = demo || state(FIND) == OK;
        if (ready && state(SEND) != RUNNING && !sessionRunning) addButton(card, state(SEND) == FAILED ? "Retry" : state(SEND) == OK ? "Send the setup again" : "Send the setup",
                Ui.OXY, state(SEND) != OK, new Runnable() {
                    @Override
                    public void run() {
                        sendSetup();
                    }
                });
        root.addView(card);
    }

    void profileInputs(LinearLayout card, final Prefs p) {
        card.addView(Ui.gap(this, 8));
        card.addView(Ui.text(this, "Your profile was never entered, so these are the app's defaults. Change them here or in Settings, Profile:", 13, Ui.TEXT, Ui.REGULAR));
        final EditText age = num(card, "Age", p.age()), height = num(card, "Height (cm)", p.heightCm()), weight = num(card, "Weight (kg)", p.weightKg());
        addButton(card, "Save the profile", Ui.OXY, false, new Runnable() {
            @Override
            public void run() {
                p.setProfile(InputLimit.AGE.parse(age.getText().toString()), p.sex(), InputLimit.WEIGHT_KG.parse(weight.getText().toString()),
                        InputLimit.HEIGHT_CM.parse(height.getText().toString()), p.targetKcal());
                p.setProfileSet();
                render();
            }
        });
    }

    EditText num(LinearLayout parent, String label, int value) {
        parent.addView(Ui.gap(this, 6));
        parent.addView(Ui.text(this, label, 12, Ui.TEXT_3, Ui.REGULAR));
        EditText e = new EditText(this);
        e.setText(String.valueOf(value));
        e.setTextColor(Ui.TEXT);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setBackground(Ui.input(this));
        int pd = Ui.dp(this, 10);
        e.setPadding(pd, pd, pd, pd);
        parent.addView(e, Ui.matchWrap());
        return e;
    }

    void doneCard() {
        LinearLayout card = header(DONE, "6. Done");
        card.addView(Ui.gap(this, 4));
        int per = Prefs.of(this).watchPerDay();
        card.addView(Ui.text(this, state(DONE) == OK ? "Your watch is set up. " + WatchDue.explain(per) : "Once the setup is sent, the watch counts steps, sleep and heart rate for this app.",
                13, Ui.TEXT_2, Ui.REGULAR));
        if (state(DONE) == OK) addButton(card, "Open the watch page", Ui.OXY, false, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(WatchSetupActivity.this, WatchPageActivity.class));
            }
        });
        root.addView(card);
    }

    // ---------------------------------------------------------------------------------------------- alarm

    void alarmCard() {
        final Prefs p = Prefs.of(this);
        final WatchSetup.Alarm a = p.watchAlarm();
        LinearLayout card = Ui.card(this);
        card.setTag("alarm");
        card.addView(Ui.caption(this, "Alarm"));
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, a.on ? WatchSetup.clock(a.hour, a.minute) : "Off", 28, a.on ? Ui.TEXT : Ui.TEXT_3, Ui.LIGHT));
        card.addView(Ui.text(this, a.on ? WatchSetup.days(a.days) + (a.smart > 0 ? ", smart wake up to " + a.smart + " min before" : "") : "The watch does not vibrate to wake you.",
                13, Ui.TEXT_2, Ui.REGULAR));
        LinearLayout onOff = Ui.hbox(this);
        String[] oo = {"On", "Off"};
        for (int k = 0; k < 2; k++) {
            final boolean on = k == 0;
            TextView c = Ui.smallButton(this, oo[k], a.on == on ? Ui.OXY : Ui.TEXT_3);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.on = on;
                    saveAlarm(p, a);
                }
            });
            LinearLayout.LayoutParams l = Ui.weight(1);
            l.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 4), 0);
            onOff.addView(c, l);
        }
        TextView time = Ui.smallButton(this, "Time", Ui.OXY);
        time.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.TimePickerDialog(WatchSetupActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(android.widget.TimePicker tp, int hour, int minute) {
                        a.hour = hour;
                        a.minute = minute;
                        a.on = true;
                        saveAlarm(p, a);
                    }
                }, a.hour, a.minute, android.text.format.DateFormat.is24HourFormat(WatchSetupActivity.this)).show();
            }
        });
        LinearLayout.LayoutParams tl = Ui.weight(1);
        tl.setMargins(0, Ui.dp(this, 8), 0, 0);
        onOff.addView(time, tl);
        card.addView(onOff, Ui.matchWrap());
        // days, Monday first
        LinearLayout days = Ui.hbox(this);
        for (int d = 0; d < 7; d++) {
            final int bit = 1 << d;
            TextView c = Ui.smallButton(this, WatchSetup.DAYS[d].substring(0, 2), (a.days & bit) != 0 ? Ui.OXY : Ui.TEXT_3);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.days ^= bit;
                    saveAlarm(p, a);
                }
            });
            LinearLayout.LayoutParams l = Ui.weight(1);
            l.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 2), 0);
            days.addView(c, l);
        }
        card.addView(days, Ui.matchWrap());
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, "Smart wake: the watch may wake you this much earlier, in light sleep", 12, Ui.TEXT_3, Ui.REGULAR));
        LinearLayout smart = Ui.hbox(this);
        for (final int m : WatchSetup.SMART) {
            TextView c = Ui.smallButton(this, m == 0 ? "Off" : m + " min", a.smart == m ? Ui.OXY : Ui.TEXT_3);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.smart = m;
                    saveAlarm(p, a);
                }
            });
            LinearLayout.LayoutParams l = Ui.weight(1);
            l.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 4), 0);
            smart.addView(c, l);
        }
        card.addView(smart, Ui.matchWrap());
        String status = alarmNote != null ? alarmNote : demo ? "Demo mode: nothing is sent." : p.watchAlarmPending() ? "Not on the watch yet: tap Save on the watch, or it goes with the next sync."
                : "On the watch.";
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, status, 13, p.watchAlarmPending() && !demo ? Ui.ACTIVE : Ui.TEXT_2, Ui.REGULAR));
        if (!demo && p.watchAddress() != null && !sessionRunning) addButton(card, "Save on the watch", Ui.OXY, false, new Runnable() {
            @Override
            public void run() {
                sendAlarm();
            }
        });
        card.addView(Ui.gap(this, 4));
        card.addView(Ui.text(this, Explain.WATCH_ALARM, 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(card);
    }

    void saveAlarm(Prefs p, WatchSetup.Alarm a) {
        if (demo) {
            say("Demo mode: nothing is saved.");
            return;
        }
        p.setWatchAlarm(a);
        alarmNote = null;
        render();
    }

    // ---------------------------------------------------------------------------------------------- hands

    void handsCard() {
        LinearLayout card = Ui.card(this);
        card.setTag("hands");
        card.addView(Ui.caption(this, "Set the hands"));
        card.addView(Ui.gap(this, 4));
        boolean on = hands != null || demo && getIntent().getBooleanExtra("demo_hands", false);
        if (!on) {
            card.addView(Ui.text(this, Explain.WATCH_HANDS, 13, Ui.TEXT_2, Ui.REGULAR));
            if (handsNote != null) card.addView(Ui.text(this, handsNote, 13, Ui.WARN, Ui.REGULAR));
            if (demo || Prefs.of(this).watchAddress() != null && !sessionRunning) addButton(card, "Start", Ui.OXY, false, new Runnable() {
                @Override
                public void run() {
                    startHands();
                }
            });
            root.addView(card);
            return;
        }
        card.addView(Ui.text(this, (handIndex + 1) + " of 3", 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.text(this, WatchSetup.HAND_TEXT[handIndex], 15, Ui.TEXT, Ui.REGULAR));
        if (handsNote != null) card.addView(Ui.text(this, handsNote, 12, Ui.TEXT_3, Ui.REGULAR));
        final int hand = WatchSetup.HANDS[handIndex];
        // the activity hand only moves clockwise
        int[] amounts = hand == Proto.HAND_ACTIVITY ? new int[] {1, 10} : new int[] {-10, -1, 1, 10};
        LinearLayout row = Ui.hbox(this);
        for (final int amount : amounts) {
            TextView c = Ui.smallButton(this, amount > 0 ? "+" + amount : String.valueOf(amount), Ui.OXY);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (hands != null) hands.offer(WatchSetup.move(hand, amount));
                }
            });
            LinearLayout.LayoutParams l = Ui.weight(1);
            l.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 4), 0);
            row.addView(c, l);
        }
        card.addView(row, Ui.matchWrap());
        card.addView(Ui.text(this, "Plus turns clockwise. The size of one step is the watch's own; try 1 first.", 12, Ui.TEXT_3, Ui.REGULAR));
        if (handIndex > 0) addButton(card, "Back", Ui.TEXT_2, false, new Runnable() {
            @Override
            public void run() {
                handIndex--;
                render();
            }
        });
        addButton(card, handIndex < 2 ? "Next" : "Done", Ui.OXY, true, new Runnable() {
            @Override
            public void run() {
                if (handIndex < 2) {
                    handIndex++;
                    render();
                } else if (hands != null) {
                    hands.offer(Session.FINISH);
                } else {
                    say("Demo mode: nothing is sent.");
                }
            }
        });
        root.addView(card);
    }

    // ---------------------------------------------------------------------------------------------- actions

    void say(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    WatchSetup.Profile demoProfile() {
        WatchSetup.Profile w = new WatchSetup.Profile();
        w.age = 38;
        w.sex = 1;
        w.weightKg = 64;
        w.heightCm = 168;
        w.year = java.time.LocalDate.now().getYear();
        return w;
    }

    void pick(Found f) {
        stopScan();
        Prefs.of(this).setWatch(f.address, f.name);
        set(FIND, OK, f.name != null ? f.name : f.address.substring(0, 2) + "...");
        refreshKnown();
        render();
        if (state(PAIR) != OK) pair();
    }

    /** Android's pairing: a bond with the watch, its dialog over this screen; the receiver hears the end. */
    void pair() {
        final String address = Prefs.of(this).watchAddress();
        if (address == null) return;
        BluetoothAdapter a = RingScanner.adapter(this);
        if (a == null || !a.isEnabled()) {
            set(PAIR, FAILED, WatchSync.BLUETOOTH_OFF);
            render();
            return;
        }
        BluetoothDevice d;
        try {
            d = a.getRemoteDevice(address);
            if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                set(PAIR, OK, "Paired with this phone");
                render();
                return;
            }
            if (bondReceiver == null) {
                bondReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        BluetoothDevice dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (dev == null || !address.equals(dev.getAddress())) return;
                        int s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                        if (s == BluetoothDevice.BOND_BONDED) set(PAIR, OK, "Paired with this phone");
                        else if (s == BluetoothDevice.BOND_NONE) set(PAIR, FAILED, "Not paired. Accept the request on the phone, keep the watch close, and tap Retry.");
                        render();
                    }
                };
                registerReceiver(bondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
            }
            boolean started = d.createBond();
            set(PAIR, started ? RUNNING : FAILED, started ? "Waiting for the pairing" : "Android did not start the pairing. Tap Retry.");
        } catch (RuntimeException e) {
            set(PAIR, FAILED, "Android did not start the pairing (" + e.getClass().getSimpleName() + "). Tap Retry.");
        }
        render();
    }

    /** Which card a session step belongs to. */
    static String cardOf(String key) {
        if (Session.HELLO.equals(key) || Session.SIGN_IN.equals(key)) return SIGN;
        if (Session.ACTIVITY.equals(key) || Session.MOVEMENT.equals(key) || Session.HEART.equals(key)) return FIRST;
        return SEND;
    }

    void sendSetup() {
        if (demo) {
            say("Demo mode: nothing is sent.");
            return;
        }
        if (sessionRunning) return;
        sessionRunning = true;
        for (String k : new String[] {SIGN, SEND, FIRST, DONE}) set(k, WAITING, null);
        set(SIGN, RUNNING, "Connecting");
        render();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] last = {null};
                String r = WatchSync.setup(app, new Session.Progress() {
                    @Override
                    public void step(final String key, final int s) {
                        last[0] = key;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                progress(key, s);
                            }
                        });
                    }
                });
                final String res = r;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        sessionRunning = false;
                        finished(res);
                    }
                });
            }
        }, "watch setup").start();
    }

    void progress(String key, int s) {
        String card = cardOf(key);
        if (s == Session.STARTED) {
            // the watch went past the sign in: it was answered (or not asked for)
            if (!SIGN.equals(card) && state(SIGN) != OK) set(SIGN, OK, null);
            if (FIRST.equals(card) && state(SEND) != OK) set(SEND, state(SEND) == FAILED ? FAILED : OK, null);
            set(card, RUNNING, "Sending: " + key);
        } else if (s == Session.FAILED) {
            set(card, FAILED, null);
        } else if (WatchSetup.FINISHED.equals(key)) {
            set(SEND, OK, "The watch took the setup");
        } else if (Session.SIGN_IN.equals(key)) {
            set(SIGN, OK, "Accepted");
        } else if (Session.HEART.equals(key)) {
            set(FIRST, OK, null);
        }
        render();
    }

    void finished(String result) {
        if (result == null) {
            set(SIGN, OK, note.get(SIGN));
            set(SEND, OK, "The watch took the setup");
            set(FIRST, OK, "Synced. The watch page shows what came.");
            set(DONE, OK, null);
        } else {
            boolean marked = false;
            for (String k : new String[] {SIGN, SEND, FIRST}) {
                if (state(k) == FAILED || state(k) == RUNNING) {
                    set(k, FAILED, result);
                    marked = true;
                    break;
                }
            }
            if (!marked) set(SIGN, FAILED, result);
        }
        refreshKnown();
        render();
    }

    void sendAlarm() {
        if (sessionRunning) return;
        sessionRunning = true;
        alarmNote = "Sending the alarm";
        render();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = WatchSync.alarm(app);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        sessionRunning = false;
                        alarmNote = r == null ? "On the watch." : r;
                        render();
                    }
                });
            }
        }, "watch alarm").start();
    }

    void startHands() {
        if (demo) {
            say("Demo mode: nothing is sent.");
            return;
        }
        if (sessionRunning) return;
        sessionRunning = true;
        handIndex = 0;
        handsNote = "Connecting, the hands go to their home position";
        final LinkedBlockingQueue<Session.Step> q = hands = new LinkedBlockingQueue<Session.Step>();
        render();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = WatchSync.hands(app, new Session.More() {
                    @Override
                    public Session.Step next(long ms) throws InterruptedException {
                        return q.poll(ms, TimeUnit.MILLISECONDS);
                    }
                }, new Session.Progress() {
                    @Override
                    public void step(String key, int s) {
                        if (WatchSetup.HANDS_START.equals(key) && s == Session.DONE) {
                            h.post(new Runnable() {
                                @Override
                                public void run() {
                                    handsNote = null;
                                    render();
                                }
                            });
                        }
                    }
                });
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        sessionRunning = false;
                        hands = null;
                        handsNote = r == null ? "Done. The watch shows the phone's time again." : r;
                        render();
                    }
                });
            }
        }, "watch hands").start();
    }

    // ------------------------------------------------------------------------------------------------ scan

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (scan == null) return;
            boolean over = SystemClock.elapsedRealtime() >= scanUntil;
            if (over) stopScan();
            int n;
            synchronized (found) {
                n = found.size();
            }
            // redrawn only when something new was heard or the scan ended, so typing on the screen is not disturbed
            if (over || n != shownCount) {
                shownCount = n;
                render();
            }
            if (scan != null) h.postDelayed(this, 1000);
        }
    };

    void startScan() {
        if (demo || !resumed || scan != null || state(FIND) == OK) return;
        if (!Permissions.granted(this)) {
            Permissions.request(this);
            return;
        }
        BluetoothAdapter a = RingScanner.adapter(this);
        if (a == null || !a.isEnabled()) {
            set(FIND, FAILED, "Bluetooth is off. Switch it on to find the watch.");
            render();
            return;
        }
        if (RingScanner.startWaitMs() > 0) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startScan();
                }
            }, RingScanner.startWaitMs());
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
                        set(FIND, FAILED, "Android stopped the scan (error " + code + "). Tap Scan again in a minute.");
                        render();
                    }
                });
            }
        });
        if (scan == null) return;
        set(FIND, RUNNING, null);
        shownCount = -1;
        scanUntil = SystemClock.elapsedRealtime() + 10000;
        h.post(tick);
    }

    void stopScan() {
        if (scan != null) scan.stop();
        scan = null;
        if (state(FIND) == RUNNING) set(FIND, WAITING, null);
    }

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
        boolean withings = false;
        List<ParcelUuid> uuids = rec == null ? null : rec.getServiceUuids();
        if (uuids != null) for (ParcelUuid u : uuids) withings |= WatchSync.SERVICE.equals(u.getUuid());
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        withings |= low.startsWith("steel") || low.startsWith("withings") || low.startsWith("activit") || low.startsWith("scanwatch");
        synchronized (found) {
            Found f = found.get(addr);
            if (f == null) found.put(addr, f = new Found(addr));
            f.rssi = r.getRssi();
            if (name != null) f.name = name;
            f.withings |= withings;
        }
    }
}
