package app.atvos.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * atvOS's logo, drawn the way tvOS Settings draws its own: a grey tile with tvOS's continuous
 * corners and the wordmark cut out of it in the page's own colour. Dark grey on lighter grey on a
 * dark page, the other way round on a light one (the colours are tvOS's, measured).
 */
final class Logo {

    private static final int TILE_DARK = 0xFF555555, TILE_LIGHT = 0xFF7D7C7D;
    /** The wordmark's width, as a share of the tile's. */
    private static final float WORDMARK = 0.62f, WORDMARK_ASPECT = 1093f / 3930f; // its viewport's
    /** The corners' radius as a share of the side, and how square they are (2 is a circle): tvOS's tile, traced. */
    private static final float CORNER = 0.222f, SQUARENESS = 2.2f;

    private final Drawable wordmark;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tile = new Path();
    private final RectF tileFor = new RectF();

    Logo(Context context) {
        Drawable d = context.getDrawable(R.drawable.wordmark);
        wordmark = d != null ? d.mutate() : null;
    }

    /** The logo filling the square {@code r}, on a page of colour {@code page}. */
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

    /** A rounded square whose corners are superellipse quarters (|x|^n + |y|^n = 1), as tvOS's are. */
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
