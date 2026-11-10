package app.openring.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A small drawing of a device for Getting started and the device choice (issue #373): a ring, a watch with hands, a
 * phone. Drawn in code in the app's palette on a soft disc of the same colour, so it needs no files and no brand logo.
 */
final class DeviceIcon extends View {
    static final int RING = 0, WATCH = 1, PHONE = 2;
    private final int kind;
    private final int color;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    DeviceIcon(Context c, int kind) {
        super(c);
        this.kind = kind;
        color = kind == RING ? Ui.SLEEP : kind == WATCH ? Ui.OXY : Ui.ACTIVE;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h), cx = w / 2, cy = h / 2;
        // the soft disc behind
        fill.setColor(Ui.alpha(color, 0.14f));
        cv.drawCircle(cx, cy, s / 2, fill);
        float u = s / 100f;
        line.setStrokeWidth(5 * u);
        if (kind == RING) {
            // a band seen at a slant: a thick ellipse, its inner edge, and the sensor bump inside
            line.setStrokeWidth(9 * u);
            r.set(cx - 28 * u, cy - 20 * u, cx + 28 * u, cy + 20 * u);
            cv.drawOval(r, line);
            line.setStrokeWidth(3 * u);
            line.setColor(Ui.alpha(color, 0.55f));
            r.set(cx - 20 * u, cy - 12 * u, cx + 20 * u, cy + 12 * u);
            cv.drawOval(r, line);
            line.setColor(color);
            fill.setColor(color);
            r.set(cx - 7 * u, cy + 6 * u, cx + 7 * u, cy + 13 * u);
            cv.drawRoundRect(r, 3 * u, 3 * u, fill);
        } else if (kind == WATCH) {
            // strap above and below, a round case, two hands at ten past ten and a small dial
            fill.setColor(Ui.alpha(color, 0.55f));
            r.set(cx - 11 * u, cy - 40 * u, cx + 11 * u, cy - 18 * u);
            cv.drawRoundRect(r, 4 * u, 4 * u, fill);
            r.set(cx - 11 * u, cy + 18 * u, cx + 11 * u, cy + 40 * u);
            cv.drawRoundRect(r, 4 * u, 4 * u, fill);
            fill.setColor(Ui.BG);
            cv.drawCircle(cx, cy, 24 * u, fill);
            line.setStrokeWidth(6 * u);
            cv.drawCircle(cx, cy, 24 * u, line);
            line.setStrokeWidth(4 * u);
            cv.drawLine(cx, cy, cx - 11 * u, cy - 7 * u, line);
            cv.drawLine(cx, cy, cx + 12 * u, cy - 9 * u, line);
            line.setStrokeWidth(2.5f * u);
            cv.drawCircle(cx, cy + 11 * u, 5 * u, line);
            fill.setColor(color);
            cv.drawCircle(cx, cy, 3 * u, fill);
        } else {
            // a phone: rounded body, the screen, the speaker slot
            line.setStrokeWidth(6 * u);
            r.set(cx - 18 * u, cy - 32 * u, cx + 18 * u, cy + 32 * u);
            cv.drawRoundRect(r, 7 * u, 7 * u, line);
            fill.setColor(Ui.alpha(color, 0.35f));
            r.set(cx - 11 * u, cy - 21 * u, cx + 11 * u, cy + 20 * u);
            cv.drawRoundRect(r, 2 * u, 2 * u, fill);
            line.setStrokeWidth(3 * u);
            cv.drawLine(cx - 5 * u, cy - 26 * u, cx + 5 * u, cy - 26 * u, line);
        }
    }
}
