package app.openring.ui.games;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

import app.openring.ble.RingManager;
import app.openring.core.Tilt;
import app.openring.ui.Ui;

/**
 * Game mode: the ring streams its accelerometer, the hand's tilt becomes a joystick. To add a
 * game, write a {@link Game} subclass and add a factory line to {@link #create(int)}.
 */
public final class GamesActivity extends Activity implements RingManager.MotionListener {
    static final int COUNT = 3;

    static Game create(int i) {
        switch (i) {
            case 0: return new AsteroidDodge();
            case 1: return new OrbCatcher();
            default: return new MotionCheck();
        }
    }

    private final Tilt tilt = new Tilt();
    private boolean streaming;
    private String ringState = "Connecting to your ring";
    private GameView view;
    private SharedPreferences sp;

    /** Opens one game full screen; back returns to the Play tab. */
    public static void start(Context c, int game) {
        c.startActivity(new android.content.Intent(c, GamesActivity.class).putExtra("game", game));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("games", Context.MODE_PRIVATE);
        tilt.flipX = sp.getBoolean("flip_x", false);
        tilt.flipY = sp.getBoolean("flip_y", false);
        int i = Math.max(0, Math.min(COUNT - 1, getIntent().getIntExtra("game", 0)));
        view = new GameView(this, create(i), i);
        setContentView(view);
    }

    // the ring is only held while a game is in front, so syncs and live work after leaving it
    @Override
    protected void onResume() {
        super.onResume();
        streaming = false;
        RingManager.get(this).startMotion(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RingManager.get(this).stopMotion(this);
    }

    // ---------------------------------------------------------------- Play tab

    /** The Play tab content shown inside the main screen (with the normal bottom bar). */
    public static View menu(final Activity a) {
        final SharedPreferences sp = a.getSharedPreferences("games", Context.MODE_PRIVATE);
        LinearLayout l = Ui.vbox(a);
        l.addView(Ui.h1(a, "Games"));
        l.addView(Ui.body(a, "Your ring becomes a controller: tilt your hand to steer. Each round starts with 3 seconds of holding "
                + "your hand still in a comfortable pose, which becomes the centre. No ring nearby? Drag on the screen instead. "
                + "Press back to return here."));
        l.addView(Ui.gap(a, 12));
        for (int i = 0; i < COUNT; i++) {
            final int k = i;
            Game g = create(i);
            LinearLayout card = Ui.card(a);
            LinearLayout top = Ui.hbox(a);
            top.addView(Ui.h2(a, g.name()), Ui.weight(1));
            int best = sp.getInt("best_" + i, 0);
            if (best > 0) top.addView(Ui.text(a, "Best " + best, 13, Ui.ACTIVE, Ui.MEDIUM));
            card.addView(top);
            card.addView(Ui.gap(a, 4));
            card.addView(Ui.body(a, g.description()));
            card.addView(Ui.gap(a, 10));
            TextView play = Ui.button(a, "Play", Ui.OXY, true);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    start(a, k);
                }
            });
            card.addView(play, Ui.matchWrap());
            l.addView(card);
        }
        LinearLayout set = Ui.card(a);
        set.addView(Ui.caption(a, "Controls"));
        final Switch fx = new Switch(a);
        fx.setText("Flip left and right");
        fx.setTextColor(Ui.TEXT);
        fx.setChecked(sp.getBoolean("flip_x", false));
        fx.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                sp.edit().putBoolean("flip_x", on).apply();
            }
        });
        set.addView(fx);
        Switch fy = new Switch(a);
        fy.setText("Flip up and down");
        fy.setTextColor(Ui.TEXT);
        fy.setChecked(sp.getBoolean("flip_y", false));
        fy.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                sp.edit().putBoolean("flip_y", on).apply();
            }
        });
        set.addView(fy);
        set.addView(Ui.text(a, "The ring connects when a game starts and is released when you leave it. Motion streaming "
                + "may not be supported by every ring firmware; touch steering always works.", 12, Ui.TEXT_3, Ui.REGULAR));
        l.addView(set);
        return l;
    }

    // ---------------------------------------------------------------- ring motion

    @Override
    public void onSamples(List<int[]> xyz) {
        for (int[] s : xyz) {
            tilt.feed(s[0], s[1], s[2]);
            if (view != null && view.calibrating()) tilt.calibrateSample();
        }
        if (!streaming) {
            streaming = true;
            ringState = "Ring motion streaming";
        }
    }

    @Override
    public void onMotionState(String state, boolean s) {
        streaming = s;
        ringState = state;
        if (view != null) view.ringStateChanged(s);
    }

    // ------------------------------------------------------------------- game view

    final class GameView extends View {
        final Game game;
        final int index;
        final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG), sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        long last, calibUntil, connectUntil;
        boolean started, saved;
        float touchX = Float.NaN, touchY;

        GameView(Context c, Game g, int index) {
            super(c);
            game = g;
            this.index = index;
            txt.setColor(Ui.TEXT);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Ui.LIGHT);
            sub.setColor(Ui.TEXT_2);
            sub.setTextAlign(Paint.Align.CENTER);
            startRound();
        }

        void startRound() {
            started = false;
            saved = false;
            long now = SystemClock.uptimeMillis();
            calibUntil = now + 3000;
            // give the ring up to 10 s to connect before the round starts on touch controls
            if (connectUntil == 0) connectUntil = now + 10000;
            tilt.startCalibration();
            last = 0;
            invalidate();
        }

        void ringStateChanged(boolean nowStreaming) {
            // motion arrived (or the ring gave up): restart the centring countdown from here
            if (!started) {
                calibUntil = SystemClock.uptimeMillis() + 3000;
                if (!nowStreaming) connectUntil = 0;
                tilt.startCalibration();
            }
            invalidate();
        }

        boolean calibrating() {
            return !started;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            game.reset(w, h);
        }

        float[] stick() {
            if (!Float.isNaN(touchX)) {
                float sx = (touchX - getWidth() / 2f) / (getWidth() * 0.35f);
                float sy = (touchY - getHeight() / 2f) / (getHeight() * 0.35f);
                return new float[] {clamp(sx), game.usesVertical() ? clamp(sy) : 0};
            }
            double[] s = tilt.stick();
            return new float[] {(float) s[0], game.usesVertical() ? (float) s[1] : 0};
        }

        void drawRingState(Canvas c, float w, float h) {
            sub.setTextSize(w * 0.034f);
            // wrap the ring status into up to three short lines at the bottom
            String[] words = ringState.split(" ");
            java.util.List<String> lines = new java.util.ArrayList<String>();
            StringBuilder cur = new StringBuilder();
            for (String wd : words) {
                if (cur.length() > 0 && cur.length() + wd.length() > 44) {
                    lines.add(cur.toString());
                    cur.setLength(0);
                }
                if (cur.length() > 0) cur.append(' ');
                cur.append(wd);
            }
            if (cur.length() > 0) lines.add(cur.toString());
            int nl = Math.min(3, lines.size());
            for (int i = 0; i < nl; i++) c.drawText(lines.get(i), w / 2, h * 0.86f + i * sub.getTextSize() * 1.35f, sub);
        }

        float clamp(float v) {
            return Math.max(-1, Math.min(1, v));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (game.over() && e.getAction() == MotionEvent.ACTION_UP) {
                game.reset(getWidth(), getHeight());
                startRound();
                return true;
            }
            if (!started && !streaming) connectUntil = 0; // touching means: play with touch now
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                touchX = Float.NaN;
            } else {
                touchX = e.getX();
                touchY = e.getY();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float w = getWidth(), h = getHeight();
            if (!started) {
                game.draw(c);
                c.drawColor(0xAA05070F);
                if (!streaming && now < connectUntil) {
                    calibUntil = now + 3000;
                    txt.setTextSize(w * 0.06f);
                    c.drawText("Connecting to your ring", w / 2, h * 0.45f, txt);
                    sub.setTextSize(w * 0.04f);
                    c.drawText("or touch the screen to play with touch", w / 2, h * 0.52f, sub);
                    drawRingState(c, w, h);
                    postInvalidateOnAnimation();
                    return;
                }
                long left = Math.max(0, calibUntil - now);
                txt.setTextSize(w * 0.07f);
                c.drawText(streaming ? "Hold your hand still" : "Drag on the screen to steer", w / 2, h * 0.42f, txt);
                txt.setTextSize(w * 0.2f);
                c.drawText(String.valueOf((left + 999) / 1000), w / 2, h * 0.58f, txt);
                sub.setTextSize(w * 0.04f);
                c.drawText(streaming ? "setting the centre of your controls" : "no ring motion, touch steering", w / 2, h * 0.66f, sub);
                drawRingState(c, w, h);
                if (left == 0) {
                    if (streaming && tilt.hasSignal()) tilt.finishCalibration();
                    started = true;
                    last = now;
                }
                postInvalidateOnAnimation();
                return;
            }
            float dt = Math.min(0.05f, (now - last) / 1000f);
            last = now;
            float[] s = stick();
            game.update(dt, s[0], s[1]);
            game.draw(c);
            sub.setTextSize(w * 0.045f);
            sub.setTextAlign(Paint.Align.LEFT);
            c.drawText(game.name() + (game.endText().isEmpty() ? "" : "   " + game.score()), w * 0.04f, h * 0.06f, sub);
            sub.setTextAlign(Paint.Align.CENTER);
            if (game.over()) {
                if (!saved) {
                    saved = true;
                    if (game.score() > sp.getInt("best_" + index, 0)) sp.edit().putInt("best_" + index, game.score()).apply();
                }
                c.drawColor(0x9905070F);
                txt.setTextSize(w * 0.065f);
                c.drawText(game.endText(), w / 2, h * 0.45f, txt);
                sub.setTextSize(w * 0.045f);
                c.drawText("Best " + sp.getInt("best_" + index, 0) + "   ·   tap to play again", w / 2, h * 0.53f, sub);
                c.drawText("back to leave", w / 2, h * 0.58f, sub);
                return;
            }
            postInvalidateOnAnimation();
        }
    }
}
