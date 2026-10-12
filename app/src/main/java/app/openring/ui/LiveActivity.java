package app.openring.ui;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.openring.ble.RingManager;
import app.openring.core.analysis.LiveHrv;
import app.openring.core.analysis.TS;

/**
 * Live measurement: heart rate and HRV from live beats, blood oxygen on demand (experimental),
 * the ring's sensor temperature, and a breath hold timer for dry apnea training.
 */
public final class LiveActivity extends Activity implements RingManager.LiveListener {
    private TextView state, source, hrV, hrvV, spo2V, tempV, holdBtn, holdTime, summary;
    private LinearLayout hrBox, spo2Box;
    private final TS hrSeries = new TS(), spo2Series = new TS();
    private final LiveHrv hrv = new LiveHrv(40);
    private final List<double[]> holds = new ArrayList<double[]>(); // {start, end} unix
    private double holdStart = Double.NaN;
    private double started;
    private double spo2Min = Double.NaN;
    private boolean spo2;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        spo2 = getIntent().getBooleanExtra("spo2", false);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 24), p, p);
        sv.addView(root);

        root.addView(Ui.h1(this, "Live measurement"));
        state = Ui.body(this, "Starting");
        root.addView(state);
        source = Ui.text(this, "", 12, Ui.TEXT_3, Ui.REGULAR);
        root.addView(source);
        root.addView(Ui.gap(this, 10));

        LinearLayout tiles = Ui.card(this);
        LinearLayout r1 = Ui.hbox(this), r2 = Ui.hbox(this);
        hrV = tile(r1, "Heart rate", "bpm", Ui.HEART);
        hrvV = tile(r1, "HRV (RMSSD)", "ms", Ui.READY);
        spo2V = tile(r2, "Blood oxygen", "%", Ui.OXY);
        tempV = tile(r2, "Sensor temp", "°C", Ui.TEMP);
        tiles.addView(r1);
        tiles.addView(Ui.gap(this, 10));
        tiles.addView(r2);
        root.addView(tiles);

        LinearLayout opt = Ui.card(this);
        Switch sw = new Switch(this);
        sw.setText("Measure blood oxygen (experimental)");
        sw.setTextColor(Ui.TEXT);
        sw.setChecked(spo2);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton bt, boolean on) {
                spo2 = on;
                state.setText("Restarting");
                RingManager.get(LiveActivity.this).startLive(LiveActivity.this, spo2);
            }
        });
        opt.addView(sw);
        opt.addView(Ui.text(this, "Blood oxygen needs a completely still hand and 30 to 60 seconds, and uses more battery. "
                + "It is a wellness reading, not a medical one.", 12, Ui.TEXT_3, Ui.REGULAR));
        root.addView(opt);

        LinearLayout hold = Ui.card(this);
        hold.addView(Ui.caption(this, "Breath hold timer"));
        holdTime = Ui.text(this, "0:00", 40, Ui.TEXT, Ui.LIGHT);
        holdTime.setGravity(Gravity.CENTER);
        hold.addView(holdTime, Ui.matchWrap());
        holdBtn = Ui.button(this, "Start hold", Ui.OXY, true);
        holdBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleHold();
            }
        });
        hold.addView(holdBtn, Ui.matchWrap());
        hold.addView(Ui.gap(this, 6));
        hold.addView(Ui.text(this, "Dry training only, sitting or lying down. Never practise breath holds in or near water alone. "
                + "Ring blood oxygen lags 10 to 30 seconds behind your body and is not a safety device.", 12, Ui.WARN, Ui.REGULAR));
        root.addView(hold);

        hrBox = chartCard(root, "Heart rate");
        spo2Box = chartCard(root, "Blood oxygen");
        summary = Ui.body(this, "");
        root.addView(summary);
        root.addView(Ui.gap(this, 12));
        TextView done = Ui.button(this, "Done", Ui.HEART, false);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(done, Ui.matchWrap());
        setContentView(sv);
        started = now();
        RingManager.get(this).startLive(this, spo2);
        h.post(tick);
    }

    TextView tile(LinearLayout row, String label, String unit, int color) {
        LinearLayout t = Ui.vbox(this);
        t.addView(Ui.caption(this, label));
        LinearLayout v = Ui.hbox(this);
        v.setGravity(Gravity.BOTTOM);
        TextView val = Ui.text(this, "--", 34, Ui.TEXT, Ui.LIGHT);
        v.addView(val);
        TextView u = Ui.text(this, " " + unit, 13, color, Ui.MEDIUM);
        u.setPadding(0, 0, 0, Ui.dp(this, 6));
        v.addView(u);
        t.addView(v);
        row.addView(t, Ui.weight(1));
        return val;
    }

    LinearLayout chartCard(LinearLayout root, String title) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.caption(this, title));
        LinearLayout box = Ui.vbox(this);
        c.addView(box, Ui.sized(this, -1, 150));
        root.addView(c);
        return box;
    }

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!Double.isNaN(holdStart)) {
                long s = (long) (now() - holdStart);
                holdTime.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
            }
            h.postDelayed(this, 250);
        }
    };

    void toggleHold() {
        if (Double.isNaN(holdStart)) {
            holdStart = now();
            holdBtn.setText("Breathe");
        } else {
            holds.add(new double[] {holdStart, now()});
            holdStart = Double.NaN;
            holdBtn.setText("Start hold");
            redraw();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tick);
        RingManager.get(this).stopLive(this);
    }

    @Override
    public void onBeat(int b, int ibi) {
        hrV.setText(String.valueOf(b));
        if (ibi == 0) {
            source.setText("Showing the ring's latest stored reading while waiting for live beats");
            return;
        }
        source.setText("Live from the ring");
        hrSeries.add(now(), b);
        hrv.add(ibi);
        double r = hrv.rmssd();
        if (!Double.isNaN(r)) hrvV.setText(String.valueOf(Math.round(r)));
        redraw();
    }

    @Override
    public void onValue(String kind, double v) {
        if ("spo2".equals(kind)) {
            spo2V.setText(String.valueOf(Math.round(v)));
            spo2Series.add(now(), v);
            spo2Min = Double.isNaN(spo2Min) ? v : Math.min(spo2Min, v);
            redraw();
        } else if ("spo2_hr".equals(kind)) {
            if (hrSeries.isEmpty()) hrV.setText(String.valueOf(Math.round(v)));
        } else if ("temp".equals(kind)) {
            tempV.setText(String.format(Locale.getDefault(), "%.1f", v));
        }
    }

    @Override
    public void onSeries(String kind, int[] v) {
        double t = now();
        if ("ibi".equals(kind)) {
            // a batch of stored beats: lay them out backwards from now at their own spacing
            double at = t;
            double[] times = new double[v.length];
            for (int i = v.length - 1; i >= 0; i--) {
                times[i] = at;
                at -= v[i] / 1000.0;
            }
            for (int i = 0; i < v.length; i++) {
                hrSeries.add(times[i], 60000.0 / v[i]);
                hrv.add(v[i]);
            }
            int n = Math.min(5, v.length);
            int[] last = java.util.Arrays.copyOfRange(v, v.length - n, v.length);
            java.util.Arrays.sort(last);
            hrV.setText(String.valueOf(Math.round(60000.0 / last[n / 2])));
            double r = hrv.rmssd();
            if (!Double.isNaN(r)) hrvV.setText(String.valueOf(Math.round(r)));
            source.setText("From the ring's measurements, updated every few seconds");
        } else if ("spo2".equals(kind)) {
            for (int i = 0; i < v.length; i++) {
                double x = v[i];
                spo2Series.add(t - (v.length - 1 - i), x);
                spo2Min = Double.isNaN(spo2Min) ? x : Math.min(spo2Min, x);
            }
            spo2V.setText(String.valueOf(v[v.length - 1]));
        }
        redraw();
    }

    @Override
    public void onLiveState(String s) {
        state.setText(s);
    }

    void redraw() {
        double t = now(), from = Math.max(started, t - 300);
        if (t - from < 30) from = t - 30;
        hrBox.removeAllViews();
        hrBox.addView(new Marked(new Charts.LineChart(this, hrSeries, from, t, Ui.HEART).noFill().unit(" bpm"), from, t), Ui.sized(this, -1, 150));
        spo2Box.removeAllViews();
        spo2Box.addView(new Marked(new Charts.LineChart(this, spo2Series, from, t, Ui.OXY).range(80, 100).unit("%"), from, t), Ui.sized(this, -1, 150));
        StringBuilder sb = new StringBuilder();
        if (!holds.isEmpty()) {
            double best = 0;
            for (double[] hd : holds) best = Math.max(best, hd[1] - hd[0]);
            sb.append(holds.size()).append(holds.size() == 1 ? " hold" : " holds").append(", longest ")
                    .append((long) best / 60).append(':').append(String.format(Locale.US, "%02d", (long) best % 60)).append(". ");
        }
        if (!Double.isNaN(spo2Min)) sb.append("Lowest blood oxygen this session ").append(Math.round(spo2Min)).append("%.");
        summary.setText(sb.toString());
    }

    /** A chart with the breath holds shaded behind it. */
    final class Marked extends LinearLayout {
        final double from, to;
        final Paint p = new Paint();

        Marked(View chart, double from, double to) {
            super(LiveActivity.this);
            this.from = from;
            this.to = to;
            setWillNotDraw(false);
            p.setColor(Ui.alpha(Ui.OXY, 0.14f));
            addView(chart, new LinearLayout.LayoutParams(-1, -1));
        }

        @Override
        protected void onDraw(Canvas c) {
            float left = Ui.dp(getContext(), 30), w = getWidth();
            List<double[]> all = new ArrayList<double[]>(holds);
            if (!Double.isNaN(holdStart)) all.add(new double[] {holdStart, now()});
            for (double[] hd : all) {
                if (hd[1] < from || hd[0] > to || to <= from) continue;
                float x0 = (float) (left + (Math.max(from, hd[0]) - from) / (to - from) * (w - left));
                float x1 = (float) (left + (Math.min(to, hd[1]) - from) / (to - from) * (w - left));
                c.drawRect(x0, 0, x1, getHeight() - Ui.dp(getContext(), 18), p);
            }
        }
    }
}
