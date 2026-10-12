package app.openring.ui.games;

import android.graphics.Canvas;

/**
 * One game. To add a game: subclass this, then list it in {@code GamesActivity.GAMES}. Input is a
 * stick in [-1, 1] on both axes (ring tilt, or touch when no ring is streaming).
 */
public abstract class Game {
    protected float w, h;
    protected int score;
    protected boolean over;

    public abstract String name();

    public abstract String description();

    /** Start a new round on a canvas of {@code w} by {@code h} pixels. */
    public void reset(float w, float h) {
        this.w = w;
        this.h = h;
        score = 0;
        over = false;
    }

    /** Advance by {@code dt} seconds. */
    public abstract void update(float dt, float sx, float sy);

    public abstract void draw(Canvas c);

    public int score() {
        return score;
    }

    public boolean over() {
        return over;
    }

    /** Text shown when a round ends. */
    public String endText() {
        return "Score " + score;
    }

    /** Whether the vertical axis matters (otherwise only left and right). */
    public boolean usesVertical() {
        return true;
    }
}
