package app.openring.ui.games;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Not a game: shows where the ring's tilt puts the stick, for testing and tuning. */
public final class MotionCheck extends Game {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float sx, sy;

    @Override
    public String name() {
        return "Motion check";
    }

    @Override
    public String description() {
        return "See how your tilt moves the cursor. Useful to test the ring before playing.";
    }

    @Override
    public void update(float dt, float sx, float sy) {
        this.sx = sx;
        this.sy = sy;
    }

    @Override
    public void draw(Canvas c) {
        c.drawColor(0xFF0A0E17);
        float cx = w / 2, cy = h / 2, r = Math.min(w, h) * 0.42f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0xFF263044);
        c.drawCircle(cx, cy, r, p);
        c.drawCircle(cx, cy, r * 0.5f, p);
        c.drawLine(cx - r, cy, cx + r, cy, p);
        c.drawLine(cx, cy - r, cx, cy + r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF6BD3B4);
        c.drawCircle(cx + sx * r, cy + sy * r, Math.min(w, h) * 0.04f, p);
        p.setColor(0xFF98A2B5);
        p.setTextSize(Math.min(w, h) * 0.05f);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(String.format(java.util.Locale.US, "x %+.2f   y %+.2f", sx, sy), cx, h - Math.min(w, h) * 0.05f, p);
    }

    @Override
    public String endText() {
        return "";
    }
}
