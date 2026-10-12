package app.openring.ui.games;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Tilt left and right to steer a ship through falling asteroids. Speed rises over time. */
public final class AsteroidDodge extends Game {
    private final Random rnd = new Random();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<float[]> rocks = new ArrayList<float[]>(); // x, y, r, spin
    private final List<float[]> stars = new ArrayList<float[]>();
    private float shipX, vx, t, spawn;

    @Override
    public String name() {
        return "Asteroid Dodge";
    }

    @Override
    public String description() {
        return "Tilt your hand left and right to steer between the rocks. It gets faster the longer you survive.";
    }

    @Override
    public boolean usesVertical() {
        return false;
    }

    @Override
    public void reset(float w, float h) {
        super.reset(w, h);
        rocks.clear();
        stars.clear();
        for (int i = 0; i < 60; i++) stars.add(new float[] {rnd.nextFloat() * w, rnd.nextFloat() * h, 0.3f + rnd.nextFloat()});
        shipX = w / 2;
        vx = 0;
        t = 0;
        spawn = 0;
    }

    @Override
    public void update(float dt, float sx, float sy) {
        if (over) return;
        t += dt;
        float speed = h * (0.35f + Math.min(1.2f, t / 40f));
        // the stick sets a target velocity; a little inertia makes steering feel smooth
        vx += (sx * w * 1.4f - vx) * Math.min(1, dt * 8);
        shipX = Math.max(w * 0.06f, Math.min(w * 0.94f, shipX + vx * dt));
        for (float[] s : stars) {
            s[1] += speed * 0.25f * s[2] * dt;
            if (s[1] > h) {
                s[1] = 0;
                s[0] = rnd.nextFloat() * w;
            }
        }
        spawn -= dt;
        if (spawn <= 0) {
            spawn = Math.max(0.22f, 0.9f - t / 60f);
            float r = w * (0.03f + rnd.nextFloat() * 0.05f);
            rocks.add(new float[] {r + rnd.nextFloat() * (w - 2 * r), -r, r, rnd.nextFloat() * 6});
        }
        float shipY = h * 0.86f, shipR = w * 0.035f;
        for (int i = rocks.size() - 1; i >= 0; i--) {
            float[] r = rocks.get(i);
            r[1] += speed * dt;
            r[3] += dt;
            if (r[1] - r[2] > h) {
                rocks.remove(i);
                continue;
            }
            float dx = r[0] - shipX, dy = r[1] - shipY;
            if (dx * dx + dy * dy < (r[2] + shipR) * (r[2] + shipR) * 0.8f) over = true;
        }
        score = (int) (t * 10);
    }

    @Override
    public void draw(Canvas c) {
        c.drawColor(0xFF05070F);
        p.setStyle(Paint.Style.FILL);
        for (float[] s : stars) {
            p.setColor(0x66FFFFFF);
            c.drawCircle(s[0], s[1], s[2] * 1.6f, p);
        }
        for (float[] r : rocks) {
            p.setColor(0xFF6B6F7E);
            c.drawCircle(r[0], r[1], r[2], p);
            p.setColor(0xFF4A4E5C);
            c.drawCircle(r[0] + r[2] * 0.3f * (float) Math.cos(r[3]), r[1] + r[2] * 0.3f * (float) Math.sin(r[3]), r[2] * 0.3f, p);
        }
        float y = h * 0.86f, s = w * 0.04f;
        Path ship = new Path();
        ship.moveTo(shipX, y - s * 1.3f);
        ship.lineTo(shipX - s, y + s);
        ship.lineTo(shipX, y + s * 0.5f);
        ship.lineTo(shipX + s, y + s);
        ship.close();
        p.setColor(0xFF6BD3B4);
        c.drawPath(ship, p);
        p.setColor(0xFFFFB46B);
        c.drawCircle(shipX, y + s * 0.9f, s * 0.25f + (float) Math.random() * s * 0.15f, p);
    }

    @Override
    public String endText() {
        return "Hit by an asteroid. Score " + score;
    }
}
