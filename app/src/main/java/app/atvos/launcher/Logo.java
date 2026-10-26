package app.atvos.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Draws the atvOS logo: a grey tile with continuous corners, with the wordmark cut out in the colour of the page. */
final class Logo {

    private static final int TILE_DARK = 0xFF555555, TILE_LIGHT = 0xFF7D7C7D;
    /** The width of the wordmark as a fraction of the tile width, and the height-to-width ratio of the wordmark. */
    private static final float WORDMARK = 0.62f, WORDMARK_ASPECT = 751f / 2634.8f;
    /** The corner radius as a fraction of the side, and the superellipse exponent of the corners (2 gives a circle). */
    private static final float CORNER = 0.222f, SQUARENESS = 2.2f;

    private final Drawable wordmark;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tile = new Path();
    private final RectF tileFor = new RectF();

    Logo(Context context) {
        Drawable d = context.getDrawable(R.drawable.wordmark);
        wordmark = d != null ? d.mutate() : null;
    }

    /** Draws the logo in the square {@code r} on a page of the colour {@code page}. */
    void draw(Canvas c, RectF r, boolean light, int page, int alpha) {
        if (!r.equals(tileFor)) {
            squircle(tile, r, r.width() * CORNER);
            tileFor.set(r);
        }
        fill.setColor(light ? TILE_LIGHT : TILE_DARK);
        fill.setAlpha(alpha);
        c.drawPath(tile, fill);
        if (wordmark == null) return;
        float w = r.width() * WORDMARK, h = w * WORDMARK_ASPECT;
        wordmark.setBounds(Math.round(r.centerX() - w / 2f), Math.round(r.centerY() - h / 2f),
                Math.round(r.centerX() + w / 2f), Math.round(r.centerY() + h / 2f));
        wordmark.setTint(page);
        wordmark.setAlpha(alpha);
        wordmark.draw(c);
    }

    /** Adds a rounded square to {@code out}. Each corner is a superellipse quarter (|x|^n + |y|^n = 1). */
    static void squircle(Path out, RectF r, float radius) {
        out.rewind();
        float[][] centres = {{r.right - radius, r.top + radius}, {r.right - radius, r.bottom - radius},
                {r.left + radius, r.bottom - radius}, {r.left + radius, r.top + radius}};
        int steps = 16;
        for (int q = 0; q < 4; q++) {
            for (int k = 0; k <= steps; k++) {
                double t = Math.toRadians(q * 90 - 90 + 90.0 * k / steps);
                double cos = Math.cos(t), sin = Math.sin(t);
                float x = centres[q][0] + (float) (radius * Math.copySign(Math.pow(Math.abs(cos), 2 / SQUARENESS), cos));
                float y = centres[q][1] + (float) (radius * Math.copySign(Math.pow(Math.abs(sin), 2 / SQUARENESS), sin));
                if (q == 0 && k == 0) out.moveTo(x, y); else out.lineTo(x, y);
            }
        }
        out.close();
    }
}
