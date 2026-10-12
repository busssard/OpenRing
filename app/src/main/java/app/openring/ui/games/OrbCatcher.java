package app.openring.ui.games;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Random;

/** Tilt in any direction to roll a ball and collect orbs before the time runs out. */
public final class OrbCatcher extends Game {
    private static final float ROUND_S = 60;
    private final Random rnd = new Random();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float bx, by, vx, vy, ox, oy, left, pulse;
    private float hx = -1, hy = -1; // hazard

    @Override
    public String name() {
        return "Orb Catcher";
    }

    @Override
    public String description() {
        return "Tilt your hand like a tray to roll the ball. Collect as many orbs as you can in 60 seconds, and avoid the red mine.";
    }

    @Override
    public void reset(float w, float h) {
        super.reset(w, h);
        bx = w / 2;
        by = h / 2;
        vx = vy = 0;
        left = ROUND_S;
        placeOrb();
    }

    void placeOrb() {
        float m = Math.min(w, h) * 0.1f;
        ox = m + rnd.nextFloat() * (w - 2 * m);
        oy = m + rnd.nextFloat() * (h - 2 * m);
        hx = m + rnd.nextFloat() * (w - 2 * m);
        hy = m + rnd.nextFloat() * (h - 2 * m);
        if (Math.hypot(hx - ox, hy - oy) < Math.min(w, h) * 0.25f || Math.hypot(hx - bx, hy - by) < Math.min(w, h) * 0.25f) placeOrb();
    }

    @Override
    public void update(float dt, float sx, float sy) {
        if (over) return;
        left -= dt;
        pulse += dt;
        if (left <= 0) {
            left = 0;
            over = true;
        }
        float acc = Math.min(w, h) * 2.2f;
        vx = (vx + sx * acc * dt) * (1 - Math.min(1, dt * 1.2f));
        vy = (vy + sy * acc * dt) * (1 - Math.min(1, dt * 1.2f));
        bx += vx * dt;
        by += vy * dt;
        float r = Math.min(w, h) * 0.035f;
        // bounce softly off the walls
        if (bx < r) { bx = r; vx = -vx * 0.4f; }
        if (bx > w - r) { bx = w - r; vx = -vx * 0.4f; }
        if (by < r) { by = r; vy = -vy * 0.4f; }
        if (by > h - r) { by = h - r; vy = -vy * 0.4f; }
        float or = Math.min(w, h) * 0.03f;
        if (Math.hypot(bx - ox, by - oy) < r + or) {
            score++;
            left = Math.min(ROUND_S, left + 1.5f);
            placeOrb();
        }
        if (hx >= 0 && Math.hypot(bx - hx, by - hy) < r + or) {
            score = Math.max(0, score - 2);
            vx = -vx * 1.5f;
            vy = -vy * 1.5f;
            hx = -1;
        }
    }

    @Override
    public void draw(Canvas c) {
        c.drawColor(0xFF0A0E17);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0xFF1E2738);
        float step = Math.min(w, h) / 8;
        for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, p);
        for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, p);
        p.setStyle(Paint.Style.FILL);
        float or = Math.min(w, h) * 0.03f;
        p.setColor(0x338E9BFF);
        c.drawCircle(ox, oy, or * (1.6f + 0.3f * (float) Math.sin(pulse * 5)), p);
        p.setColor(0xFF8E9BFF);
        c.drawCircle(ox, oy, or, p);
        if (hx >= 0) {
            p.setColor(0xFFFF6F86);
            c.drawCircle(hx, hy, or, p);
        }
        p.setColor(0xFFEEF1F6);
        c.drawCircle(bx, by, Math.min(w, h) * 0.035f, p);
        // time bar
        p.setColor(0xFF263044);
        c.drawRect(0, 0, w, 6, p);
        p.setColor(left < 10 ? 0xFFFF8A65 : 0xFF6BD3B4);
        c.drawRect(0, 0, w * left / ROUND_S, 6, p);
    }

    @Override
    public String endText() {
        return "Time is up. " + score + (score == 1 ? " orb" : " orbs");
    }
}
