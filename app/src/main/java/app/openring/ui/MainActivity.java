package app.openring.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import app.openring.R;
import app.openring.ble.RingManager;
import app.openring.data.Prefs;
import app.openring.sync.SyncJob;
import app.openring.sync.SyncService;

/** Home: date navigation, the six tabs and the sync status strip. */
public final class MainActivity extends Activity implements RingManager.Listener, Pages.Nav {
    private LocalDate date = LocalDate.now();
    private int tab = Pages.TODAY;
    private ScrollView scroll;
    private TextView dateLabel, statusText;
    private ProgressBar statusBar;
    private LinearLayout statusStrip;
    private LinearLayout nav;
    private boolean wasBusy;
    private DayData data;
    private int loadToken;
    private boolean askedPermissions;
    /** True until the person picks a date: the screen then follows the newest day with data. */
    private boolean autoDate = true;

    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_sleep, R.drawable.ic_readiness, R.drawable.ic_activity, R.drawable.ic_heart, R.drawable.ic_game};
    private static final String[] LABELS = {"Today", "Sleep", "Ready", "Activity", "Body", "Play"};
    private static final int[] COLORS = {Ui.TEXT, Ui.SLEEP, Ui.READY, Ui.ACTIVE, Ui.HEART, Ui.OXY};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) {
            tab = b.getInt("tab", Pages.TODAY);
            String d = b.getString("date");
            if (d != null) date = LocalDate.parse(d);
            autoDate = b.getBoolean("auto", true);
        }
        handleExtras(getIntent());
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.CARD);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);

        // header: date navigation and settings
        LinearLayout header = Ui.hbox(this);
        int p = Ui.dp(this, 12);
        header.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 4));
        header.addView(iconButton(R.drawable.ic_chevron_left, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepDay(date.minusDays(1));
            }
        }));
        dateLabel = Ui.text(this, "", 18, Ui.TEXT, Ui.MEDIUM);
        dateLabel.setGravity(Gravity.CENTER);
        dateLabel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoDate = true;
                setDate(LocalDate.now());
            }
        });
        header.addView(dateLabel, Ui.weight(1));
        header.addView(iconButton(R.drawable.ic_chevron_right, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!date.isBefore(LocalDate.now())) return;
                stepDay(date.plusDays(1));
            }
        }));
        header.addView(iconButton(R.drawable.ic_sync, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sync();
            }
        }));
        header.addView(iconButton(R.drawable.ic_settings, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));
        root.addView(header);

        // status strip
        statusStrip = Ui.vbox(this);
        statusStrip.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 6));
        statusText = Ui.text(this, "", 12, Ui.TEXT_2, Ui.REGULAR);
        statusText.setGravity(Gravity.CENTER);
        statusStrip.addView(statusText);
        statusBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        statusBar.setProgressTintList(ColorStateList.valueOf(Ui.SLEEP));
        statusBar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        statusBar.setMax(1000);
        statusStrip.addView(statusBar, Ui.sized(this, -1, 4));
        root.addView(statusStrip);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nav = Ui.hbox(this);
        nav.setBackgroundColor(Ui.CARD);
        nav.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        for (int i = 0; i < ICONS.length; i++) nav.addView(navItem(i), Ui.weight(1));
        root.addView(nav);
        setContentView(root);
    }

    private boolean preparingDemo;
    /** Scroll position to return to while stepping through days, -1 when none is pending. */
    private int wantedY = -1;

    void stepDay(LocalDate d) {
        if (wantedY < 0) wantedY = scroll.getScrollY();
        autoDate = false;
        setDate(d);
    }

    /**
     * Extras used by the setup screen and by the emulator screenshot test:
     * demo (boolean) starts demo mode, tab (int) opens a tab, analysis (boolean) unfolds
     * Further analysis on the Sleep page, period (int) picks its range, day (yyyy-mm-dd) a date.
     */
    void handleExtras(Intent i) {
        if (i == null) return;
        if (i.hasExtra("tab")) tab = i.getIntExtra("tab", tab);
        if (i.getBooleanExtra("analysis", false)) Pages.analysisOpen = true;
        if (i.hasExtra("period")) Pages.analysisPeriod = i.getIntExtra("period", 1);
        String day = i.getStringExtra("day");
        if (day != null) {
            try {
                date = LocalDate.parse(day);
                autoDate = false;
            } catch (RuntimeException ignored) {
            }
        }
        if (i.getBooleanExtra("demo", false)) startDemo();
        // screen test: open Settings at a card ("stages")
        String section = i.getStringExtra("settings");
        if (section != null) startActivity(new Intent(this, SettingsActivity.class).putExtra("section", section));
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleExtras(i);
        if (scroll != null) {
            scroll.scrollTo(0, 0);
            load();
        }
    }

    void startDemo() {
        preparingDemo = true;
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("Preparing demo data");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, String, Void>() {
            @Override
            protected Void doInBackground(Void... v) {
                app.openring.data.Demo.enter(MainActivity.this, new app.openring.data.Demo.Progress() {
                    @Override
                    public void step(String what) {
                        publishProgress(what);
                    }
                });
                return null;
            }

            @Override
            protected void onProgressUpdate(String... s) {
                pd.setMessage(s[0]);
            }

            @Override
            protected void onPostExecute(Void r) {
                preparingDemo = false;
                if (!isFinishing()) pd.dismiss();
                autoDate = true;
                date = LocalDate.now();
                load();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("date", date.toString());
        out.putBoolean("auto", autoDate);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs prefs = Prefs.of(this);
        if (preparingDemo) return;
        if (!prefs.demo() && (!prefs.setupDone() || !prefs.hasKey())) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        if (!prefs.demo() && !Permissions.granted(this) && !askedPermissions) {
            askedPermissions = true;
            Permissions.request(this);
        }
        SyncJob.schedule(this);
        RingManager.get(this).addListener(this);
        wasBusy = RingManager.get(this).busy();
        updateStatus();
        load();
        long since = System.currentTimeMillis() / 1000 - Math.max(prefs.lastSync(), prefs.lastAttempt());
        if (!prefs.demo() && since > 30 * 60 && !RingManager.get(this).busy() && Permissions.granted(this)) SyncService.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RingManager.get(this).removeListener(this);
    }

    void setDate(LocalDate d) {
        date = d;
        load();
    }

    void load() {
        dateLabel.setText(dateTitle(date));
        final int token = ++loadToken;
        final LocalDate d = date;
        new AsyncTask<Void, Void, DayData>() {
            String latest;

            @Override
            protected DayData doInBackground(Void... v) {
                try {
                    app.openring.data.ScreenUse.collect(MainActivity.this); // screen time is read on this phone (issue #13)
                } catch (RuntimeException ignored) {
                }
                latest = app.openring.data.Db.get(MainActivity.this).latestDay();
                return DayData.load(MainActivity.this, d);
            }

            @Override
            protected void onPostExecute(DayData result) {
                if (token != loadToken || isFinishing()) return;
                // until the person picks a date, follow the newest day with data: today if it has
                // any, else the last one. A morning sync then moves the screen to last night.
                if (autoDate && latest != null && !latest.equals(d.toString())) {
                    LocalDate l = LocalDate.parse(latest);
                    boolean newer = l.isAfter(d) && !l.isAfter(LocalDate.now());
                    if (newer || (result.empty() && l.isBefore(d))) {
                        setDate(l);
                        return;
                    }
                }
                data = result;
                render();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    static String dateTitle(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
    }

    void render() {
        if (data == null) return;
        View content;
        switch (tab) {
            case Pages.SLEEP:
                content = Pages.sleep(this, data, this);
                break;
            case Pages.READINESS:
                content = Pages.readiness(this, data);
                break;
            case Pages.ACTIVITY:
                content = Pages.activity(this, data);
                break;
            case Pages.BODY:
                content = Pages.body(this, data, this);
                break;
            case 5:
                content = app.openring.ui.games.GamesActivity.menu(this);
                break;
            default:
                content = Pages.today(this, data, this, syncLine());
        }
        if (Prefs.of(this).demo() && content instanceof LinearLayout) {
            LinearLayout banner = Ui.card(this);
            banner.setBackground(Ui.rounded(Ui.alpha(Ui.OXY, 0.14f), Ui.dp(this, 14)));
            banner.addView(Ui.text(this, "Demo: a made up person's data", 14, Ui.TEXT, Ui.MEDIUM));
            banner.addView(Ui.text(this, "Everything works like with a real ring. Leave the demo in Settings.", 12, Ui.TEXT_2, Ui.REGULAR));
            ((LinearLayout) content).addView(banner, 0);
        }
        int p = Ui.dp(this, 16);
        content.setPadding(p, Ui.dp(this, 4), p, p);
        // keep the reading position when stepping through days (issue #6); the new content has no
        // height yet, so scroll after layout. A short day (no data) does not lose the position.
        final int y = wantedY >= 0 ? wantedY : scroll.getScrollY();
        scroll.removeAllViews();
        scroll.addView(content);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, y);
                if (scroll.getScrollY() >= y) wantedY = -1;
            }
        });
        for (int i = 0; i < nav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) nav.getChildAt(i);
            int col = i == tab ? COLORS[i] : Ui.TEXT_3;
            ((ImageView) item.getChildAt(0)).setImageTintList(ColorStateList.valueOf(col));
            ((TextView) item.getChildAt(1)).setTextColor(col);
        }
    }

    String syncLine() {
        Prefs p = Prefs.of(this);
        StringBuilder sb = new StringBuilder();
        if (p.lastSync() > 0) {
            sb.append("Synced ").append(DateUtils.getRelativeTimeSpanString(p.lastSync() * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        } else {
            sb.append("Not synced yet");
        }
        if (p.battery() >= 0) sb.append("  ·  Ring battery ").append(p.battery()).append('%');
        sb.append("\nAll analysis runs on this phone");
        return sb.toString();
    }

    View navItem(final int i) {
        LinearLayout item = Ui.vbox(this);
        item.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(ICONS[i]);
        item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView t = Ui.text(this, LABELS[i], 11, Ui.TEXT_3, Ui.MEDIUM);
        item.addView(t);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(i);
            }
        });
        return item;
    }

    View iconButton(int res, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        int p = Ui.dp(this, 9);
        v.setPadding(p, p, p, p);
        v.setOnClickListener(l);
        v.setBackgroundResource(android.R.drawable.list_selector_background);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        return v;
    }

    void updateStatus() {
        RingManager m = RingManager.get(this);
        boolean busy = m.busy();
        String err = Prefs.of(this).lastSyncError();
        if (busy) {
            statusStrip.setVisibility(View.VISIBLE);
            statusText.setText(m.status());
            statusText.setTextColor(Ui.TEXT_2);
            double pr = m.progress();
            statusBar.setVisibility(View.VISIBLE);
            statusBar.setIndeterminate(pr < 0);
            if (pr >= 0) statusBar.setProgress((int) (pr * 1000));
        } else if (err != null) {
            statusStrip.setVisibility(View.VISIBLE);
            statusText.setText(err);
            statusText.setTextColor(Ui.WARN);
            statusBar.setVisibility(View.GONE);
        } else {
            statusStrip.setVisibility(View.GONE);
        }
        if (wasBusy && !busy) load();
        wasBusy = busy;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (Permissions.granted(this) && !RingManager.get(this).busy() && Prefs.of(this).lastSync() == 0) SyncService.start(this);
    }

    @Override
    public void onRingStatus() {
        updateStatus();
    }

    // ---- Pages.Nav ----
    @Override
    public void go(int t) {
        tab = t;
        wantedY = -1;
        scroll.scrollTo(0, 0);
        render();
    }

    @Override
    public void showDay(LocalDate day) {
        if (day.isAfter(LocalDate.now())) day = LocalDate.now();
        stepDay(day);
    }

    @Override
    public void live() {
        if (Prefs.of(this).demo()) {
            android.widget.Toast.makeText(this, "Live measurements need a real ring. Leave the demo in Settings to connect yours.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, LiveActivity.class));
    }

    @Override
    public void sync() {
        if (Prefs.of(this).demo()) {
            android.widget.Toast.makeText(this, "Demo mode shows made up data. Leave the demo in Settings to use your ring.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (!Permissions.granted(this)) {
            Permissions.request(this);
            return;
        }
        if (!RingManager.get(this).busy()) SyncService.start(this);
    }
}
