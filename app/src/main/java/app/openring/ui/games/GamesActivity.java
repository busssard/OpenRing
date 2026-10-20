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

    /** Opens one game full screen; back returns to Settings. */
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

    // ---------------------------------------------------------------- Settings card

    /**
     * The Ring games card in Settings (issue #15): a test of the ring's motion, always there, and
     * a switch that shows the games and their controls. Back from a game returns to Settings.
     */
    public static LinearLayout settingsCard(final Activity a) {
        final LinearLayout card = Ui.card(a);
        fill(a, card);
        return card;
    }

    /** Builds the card again (after the switch, or back from a game with a new best score). */
    public static void fill(final Activity a, final LinearLayout card) {
        card.removeAllViews();
        final SharedPreferences sp = a.getSharedPreferences("games", Context.MODE_PRIVATE);
        final app.openring.data.Prefs p = app.openring.data.Prefs.of(a);
        card.addView(Ui.caption(a, "Ring games"));
        card.addView(Ui.body(a, "Your ring as a game controller: tilt your hand to steer. Not every ring firmware sends its "
                + "movement live, so test it first."));
        TextView test = Ui.smallButton(a, "Test the ring's motion", Ui.OXY);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start(a, 2);
            }
        });
        LinearLayout.LayoutParams tl = Ui.matchWrap();
        tl.setMargins(0, Ui.dp(a, 10), 0, 0);
        card.addView(test, tl);
        card.addView(Ui.text(a, "Shows a dot that follows your hand and says at the bottom whether the ring sends its movement "
                + "(\"Ring motion streaming\") or only touch steering works.", 12, Ui.TEXT_3, Ui.REGULAR));
        Switch on = new Switch(a);
        on.setText("Show ring games");
        on.setTextColor(Ui.TEXT);
        on.setChecked(p.gamesOn());
        on.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                p.setGamesOn(v);
                fill(a, card);
            }
        });
        LinearLayout.LayoutParams ol = Ui.matchWrap();
        ol.setMargins(0, Ui.dp(a, 12), 0, 0);
        card.addView(on, ol);
        if (!p.gamesOn()) return;
        card.addView(Ui.text(a, "Each round starts with 3 seconds of holding your hand still, which becomes the centre. "
                + "No ring nearby? Drag on the screen instead. Press back to return here.", 12, Ui.TEXT_3, Ui.REGULAR));
        for (int i = 0; i < COUNT - 1; i++) {
            final int k = i;
            Game g = create(i);
            LinearLayout row = Ui.hbox(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(a, 10), 0, 0);
            LinearLayout txt = Ui.vbox(a);
            txt.addView(Ui.text(a, g.name(), 15, Ui.TEXT, Ui.MEDIUM));
            int best = sp.getInt("best_" + i, 0);
            txt.addView(Ui.text(a, g.description() + (best > 0 ? " Best " + best + "." : ""), 12, Ui.TEXT_2, Ui.REGULAR));
            row.addView(txt, Ui.weight(1));
            TextView play = Ui.smallButton(a, "Play", Ui.OXY);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    start(a, k);
                }
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
            pl.setMargins(Ui.dp(a, 10), 0, 0, 0);
            row.addView(play, pl);
            card.addView(row);
        }
        final Switch fx = new Switch(a);
        fx.setText("Flip left and right");
        fx.setTextColor(Ui.TEXT);
        fx.setChecked(sp.getBoolean("flip_x", false));
        fx.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                sp.edit().putBoolean("flip_x", v).apply();
            }
        });
        LinearLayout.LayoutParams fl = Ui.matchWrap();
        fl.setMargins(0, Ui.dp(a, 12), 0, 0);
        card.addView(fx, fl);
        Switch fy = new Switch(a);
        fy.setText("Flip up and down");
        fy.setTextColor(Ui.TEXT);
        fy.setChecked(sp.getBoolean("flip_y", false));
        fy.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                sp.edit().putBoolean("flip_y", v).apply();
            }
        });
        card.addView(fy);
        card.addView(Ui.text(a, "The ring connects when a game starts and is released when you leave it.", 12, Ui.TEXT_3, Ui.REGULAR));
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
            // wrap the ring status into up to three short lines at the bottom (SyncError.game texts fit, issue #60)
            java.util.List<String> lines = app.openring.core.SyncError.wrap(ringState, app.openring.core.SyncError.GAME_LINE);
            int nl = Math.min(app.openring.core.SyncError.GAME_LINES, lines.size());
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
