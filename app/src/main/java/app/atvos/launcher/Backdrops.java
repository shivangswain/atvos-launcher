package app.atvos.launcher;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.net.Uri;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-screen backgrounds. Behind the dock, for the focused app, in order of preference:
 * <pre>
 *   the app's TV recommendation      see {@link Recommendations}, downloaded once and cached;
 *                                    under 720p it's shown as a card with its title instead
 *   the app's splash image           when its launch screen is a real picture of 720p or more
 *   the app's own logo               large, as tvOS does: its icon's artwork, or its banner's
 *                                    logo cut from its background, over a glow in its colour
 * </pre>
 * A built-in aurora covers the moment before apps load. Each backdrop is kept sharp at screen size
 * plus a tiny blurred copy for the glass and the grid's blur.
 */
final class Backdrops {

    static final class Backdrop {
        final Bitmap sharp;
        final Bitmap blur;
        final BitmapShader blurShader;
        /** For a card layout: the sharp image, to redraw the focused card larger. */
        final BitmapShader sharpShader;
        /** Set when the image is an app's recommendation. */
        final Recommendations.Item program;
        /**
         * For a recommendation under 720p, shown as a card: where the card is (design units, see
         * {@link #CARD}) and the bottom of the title beside it. Null for a full-screen picture.
         */
        final RectF card;
        final float titleBottom;
        /** Brightness (0–1) behind the clock, the full-screen hint and the Control Center, so text can flip to dark. */
        final float clockLight, hintLight, controlsLight;

        Backdrop(Bitmap sharp, Bitmap blur, Recommendations.Item program) {
            this(sharp, blur, program, null, 0f);
        }

        Backdrop(Bitmap sharp, Bitmap blur, Recommendations.Item program, RectF card, float titleBottom) {
            this.sharp = sharp;
            this.blur = blur;
            this.blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.sharpShader = card != null ? new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
            this.program = program;
            this.card = card;
            this.titleBottom = titleBottom;
            this.clockLight = light(blur, 0.86f, 0.03f, 1f, 0.11f);
            this.hintLight = light(blur, 0.40f, 0.62f, 0.60f, 0.70f);
            this.controlsLight = light(blur, 0.72f, 0.10f, 1f, 0.53f);
        }

        /** Mean luminance of a region given as fractions of the (small, blurred) bitmap. */
        private static float light(Bitmap b, float l, float t, float r, float bottom) {
            int bw = b.getWidth(), bh = b.getHeight();
            int x0 = Math.min((int) (l * bw), bw - 1), x1 = Math.min(Math.max(x0 + 1, (int) (r * bw)), bw);
            int y0 = Math.min((int) (t * bh), bh - 1), y1 = Math.min(Math.max(y0 + 1, (int) (bottom * bh)), bh);
            int w = x1 - x0, n = w * (y1 - y0);
            int[] px = new int[n];
            b.getPixels(px, 0, w, x0, y0, w, y1 - y0);
            double sum = 0;
            for (int p : px) sum += 0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255);
            return (float) (sum / n / 255.0);
        }
    }

    static final String DEFAULT = "default";
    /** The card a small recommendation is shown on, in 1920-wide design units, and its corner radius. */
    static final RectF CARD = new RectF(92.5f, 172f, 828.5f, 586f);
    static final float CARD_R = 26f;
    /**
     * Every app's logo backdrop shows its logo at the area of a square this many design units wide,
     * icon or banner, round badge or long wordmark: about what the smallest banners (Sony LIV's)
     * allow at 1.5x their pixels, so all apps look the same size and none look soft.
     */
    private static final float LOGO_SIZE = 160f;
    // Per-app switches for the two recommendation sources (sets of packages switched off).
    private static final String NO_WATCH_NEXT = "no_watch_next", NO_PREVIEWS = "no_previews";
    private static final String REMOTE = "rec:", APP = "app:";
    private static final int MAX_DOWNLOAD = 16 << 20;
    private static final int MAX_CACHED = 48;

    private final ContentResolver resolver;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final File downloads;
    private final int width, height;
    private final Backdrop fallback;

    // Written on the IO thread, read on the UI thread.
    private volatile Map<String, Recommendations.Item> recommended = Collections.emptyMap();
    // Both threads: images on disk. prefetch() adds as it downloads; scan() reconciles with the disk.
    private final Set<String> downloaded = ConcurrentHashMap.newKeySet();
    private volatile Set<String> watchNext = Collections.emptySet(), previews = Collections.emptySet();

    // Network thread only: when each URL last failed, so a dead network isn't hammered.
    private final Map<String, Long> failedAt = new HashMap<>();
    private static final long RETRY_MS = 15 * 60_000L;

    // UI thread only. Backdrops are ~8 MB each (1080p), so keep just a few: fewer on low-RAM TVs.
    private final int cacheSize;
    private final Map<String, Backdrop> cache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Backdrop> eldest) {
            return size() > cacheSize;
        }
    };

    Backdrops(Context context, int width, int height) {
        this.resolver = context.getContentResolver();
        this.pm = context.getPackageManager();
        this.prefs = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        this.downloads = new File(context.getCacheDir(), "shelf");
        // Rendered at no more than 1080p: some TVs draw their UI at 4K, where each backdrop would be
        // 33 MB of texture for no visible gain behind the UI.
        float k = Math.min(1f, 1920f / Math.max(1, width));
        this.width = Math.max(1, Math.round(width * k));
        this.height = Math.max(1, Math.round(height * k));
        ActivityManager am = context.getSystemService(ActivityManager.class);
        this.cacheSize = am != null && am.isLowRamDevice() ? 3 : 6;
        Bitmap aurora = aurora();
        Bitmap blurred = blur(aurora);
        this.fallback = new Backdrop(toHardware(aurora), blurred, null);
    }

    /** Re-reads app recommendations. Keys include the image URL, so a new image is picked up. IO thread. */
    void scan(Context context) {
        Recommendations.Found found = Recommendations.load(context, off(NO_WATCH_NEXT), off(NO_PREVIEWS));
        // Add what's on disk and drop what's gone, but never clear the set: a download finishing on
        // the net thread meanwhile must not be lost.
        for (Recommendations.Item item : found.best.values()) if (cacheFile(item.image()).isFile()) downloaded.add(item.image());
        downloaded.removeIf(url -> !cacheFile(url).isFile());
        watchNext = found.watchNext;
        previews = found.previews;
        recommended = found.best;
    }

    /**
     * The recommendation sources an app has, as {@link Recommendations#WATCH_NEXT} and
     * {@link Recommendations#PREVIEWS} bits: the ones it publishes, and any switched off.
     */
    int sources(String pkg) {
        int bits = 0;
        if (watchNext.contains(pkg) || !sourceOn(pkg, Recommendations.WATCH_NEXT)) bits |= Recommendations.WATCH_NEXT;
        if (previews.contains(pkg) || !sourceOn(pkg, Recommendations.PREVIEWS)) bits |= Recommendations.PREVIEWS;
        return bits;
    }

    boolean sourceOn(String pkg, int source) {
        return !off(source == Recommendations.WATCH_NEXT ? NO_WATCH_NEXT : NO_PREVIEWS).contains(pkg);
    }

    /** Switches one of an app's sources on or off; takes effect on the next {@link #scan}. */
    void setSource(String pkg, int source, boolean on) {
        String key = source == Recommendations.WATCH_NEXT ? NO_WATCH_NEXT : NO_PREVIEWS;
        Set<String> set = new HashSet<>(off(key));
        if (on) set.remove(pkg); else set.add(pkg);
        prefs.edit().putStringSet(key, set).apply();
    }

    private Set<String> off(String key) {
        return prefs.getStringSet(key, Collections.emptySet());
    }

    /**
     * Downloads recommendation images that aren't cached yet, then trims the cache. A shelf only
     * appears once its image is on disk, so {@code onReady} runs after each new download. Network thread.
     */
    void prefetch(Runnable onReady) {
        for (Recommendations.Item item : recommended.values()) {
            String url = item.image();
            if (downloaded.contains(url)) continue;
            Long failed = failedAt.get(url);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) continue;
            if (fetch(url, item.pkg()) != null) {
                downloaded.add(url);
                onReady.run();
            } else {
                failedAt.put(url, System.currentTimeMillis());
            }
        }
        // Trim to the most recently used images (fetch() touches a file on every use), never deleting
        // one a current recommendation shows.
        File[] cached = downloads.listFiles();
        if (cached != null && cached.length > MAX_CACHED) {
            Set<String> inUse = new HashSet<>();
            for (Recommendations.Item item : recommended.values()) inUse.add(cacheFile(item.image()).getName());
            long[] used = new long[cached.length];
            Integer[] order = new Integer[cached.length];
            for (int i = 0; i < cached.length; i++) {
                used[i] = cached[i].lastModified();
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingLong(i -> used[i]));
            int excess = cached.length - MAX_CACHED;
            for (int i = 0; i < order.length && excess > 0; i++) {
                File f = cached[order[i]];
                if (!inUse.contains(f.getName()) && f.delete()) excess--;
            }
        }
    }

    private String shelfKey(String pkg) {
        Recommendations.Item item = recommended.get(pkg);
        return item != null && downloaded.contains(item.image()) ? REMOTE + item.image() : null;
    }

    /** What sits behind the dock for an app: its top shelf, else its splash picture or logo. */
    String backdropKey(String pkg) {
        String shelf = shelfKey(pkg);
        return shelf != null ? shelf : APP + pkg;
    }

    Recommendations.Item recommendation(String pkg) {
        return recommended.get(pkg);
    }

    Backdrop cached(String key) {
        return DEFAULT.equals(key) ? fallback : cache.get(key);
    }

    void put(String key, Backdrop b) {
        cache.put(key, b);
    }


    /**
     * Prepares the backdrop behind {@code key} (images are already on disk), or null. Running out
     * of memory (a huge image, a low-RAM TV) just means no backdrop this time. IO thread.
     */
    Backdrop load(String key, Recommendations.Item program) {
        try {
            return prepare(key, program);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private Backdrop prepare(String key, Recommendations.Item program) {
        if (DEFAULT.equals(key)) return fallback;
        if (key.startsWith(APP)) return appLogo(key.substring(APP.length()));
        if (!key.startsWith(REMOTE)) return null;
        File file = cacheFile(key.substring(REMOTE.length()));
        if (!file.isFile()) return null;

        // Just the header first: the size decides between full screen and the card layout.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        boolean hd = bounds.outWidth >= 1216 && bounds.outHeight >= 684; // 720p, give or take 5%

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= width && bounds.outHeight / (opts.inSampleSize * 2) >= height) {
            opts.inSampleSize *= 2;
        }
        Bitmap src = BitmapFactory.decodeFile(file.getPath(), opts);
        if (src == null) return null;
        if (!hd) {
            Backdrop card = poster(src, program);
            src.recycle();
            return card;
        }
        Bitmap sharp = cover(src, width, height);
        if (sharp != src) src.recycle();
        Bitmap blurred = blur(sharp);
        return new Backdrop(toHardware(sharp), blurred, program);
    }

    /**
     * For pictures under 720p, which would look soft stretched across the screen: the picture as a
     * 16:9 card on the left (centre-cropped, which also drops letterbox bars) with the program's
     * name beside it, over a blurred, darkened copy of the picture.
     */
    private Backdrop poster(Bitmap src, Recommendations.Item program) {
        String title = program != null ? program.title() : null;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = width / 1920f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new LightingColorFilter(0xFF8C8C8C, 0));
        Bitmap field = blur(src);
        c.drawBitmap(field, null, new RectF(0, 0, width, height), p);
        field.recycle();
        p.setColorFilter(null);

        RectF card = new RectF(CARD.left * s, CARD.top * s, CARD.right * s, CARD.bottom * s);
        float radius = CARD_R * s, titleBottom = CARD.centerY();
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(0x8C000000);
        shade.setMaskFilter(new BlurMaskFilter(36f * s, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(card.left, card.top + 18f * s, card.right, card.bottom + 18f * s, radius, radius, shade);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float k = Math.max(card.width() / src.getWidth(), card.height() / src.getHeight());
        Matrix m = new Matrix();
        m.setScale(k, k);
        m.postTranslate(card.centerX() - src.getWidth() * k / 2f, card.centerY() - src.getHeight() * k / 2f);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        c.drawRoundRect(card, radius, radius, p);

        if (title != null && !title.isEmpty()) {
            TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
            text.setTextSize(58f * s);
            float left = card.right + 60f * s;
            int room = Math.round(width - 92.5f * s - left);
            StaticLayout layout = StaticLayout.Builder.obtain(title, 0, title.length(), text, room)
                    .setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            c.save();
            c.translate(left, card.centerY() - layout.getHeight() / 2f);
            layout.draw(c);
            c.restore();
            titleBottom = (card.centerY() + layout.getHeight() / 2f) / s;
        }
        Bitmap blurred = blur(out);
        return new Backdrop(toHardware(out), blurred, program, CARD, titleBottom);
    }

    /**
     * For apps without a top shelf, as tvOS does: the app's splash picture if it has one, else its
     * logo large over a field of its colour: the icon's own artwork when that stands alone, else the
     * banner's logo, cut from its background. Always from the highest-density resources the app ships.
     */
    private Backdrop appLogo(String pkg) {
        Bitmap out;
        try {
            ActivityInfo ai = launchActivity(pkg);
            if (ai == null) return null;
            Resources res = pm.getResourcesForApplication(ai.applicationInfo);
            out = splash(res, ai);
            if (out == null) out = iconScene(res, ai);
            if (out == null) out = bannerScene(res, ai);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null; // another app's resources: anything can go wrong, and the aurora still works
        }
        if (out == null) return null;
        Bitmap blurred = blur(out);
        return new Backdrop(toHardware(out), blurred, null);
    }

    private ActivityInfo launchActivity(String pkg) {
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        ResolveInfo ri = launch != null ? pm.resolveActivity(launch, 0) : null;
        return ri != null ? ri.activityInfo : null;
    }

    /**
     * The icon's logo without its backdrop shape (an adaptive icon's foreground layer, or a legacy
     * icon drawn on transparency), over a field lit in the brand colour of the app's tile. Null when
     * the icon has no stand-alone logo (a full picture, or a logo on its own card) or too few pixels
     * to show it large; the banner is used instead.
     */
    private Bitmap iconScene(Resources res, ActivityInfo ai) {
        int iconId = ai.getIconResource();
        if (iconId == 0) return null;
        try {
            Drawable icon = res.getDrawableForDensity(iconId, DisplayMetrics.DENSITY_XXXHIGH, null);
            boolean adaptive = icon instanceof AdaptiveIconDrawable;
            Drawable fg = adaptive ? ((AdaptiveIconDrawable) icon).getForeground() : icon;
            // A legacy icon's canvas is the icon itself, so its logo fills more of it.
            Glyph glyph = fg != null ? Glyph.measure(fg, adaptive ? 0.5f : 0.6f) : null;
            if (glyph == null) return null;
            int bitmap = bitmapWidth(fg);
            float maxCanvas = bitmap > 0 ? bitmap * 1.5f : Float.MAX_VALUE; // enlarged no more than 1.5x, so it stays sharp
            int bannerId = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
            Drawable banner = bannerId != 0 ? res.getDrawable(bannerId, null) : null;
            int brand = brandColor(banner != null ? banner : icon);
            return logoScene(fg, glyph, LOGO_SIZE, maxCanvas, 0.5f, brand, glyph.light < 0.25f); // a dark logo goes on a pale field
        } catch (RuntimeException e) {
            return null; // another app's resources: anything can go wrong, and the banner still works
        }
    }

    /**
     * The banner (or, without one, the icon) the same way: its own background is cut away, so its
     * logo floats over a field lit in that background's colour, light or dark as the tile is. Art
     * that's a picture rather than a logo on a background is shown whole, as a card.
     */
    private Bitmap bannerScene(Resources res, ActivityInfo ai) {
        int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        if (id == 0) id = ai.getIconResource();
        if (id == 0) return null;
        Drawable art = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        if (art == null) return null;

        // Cut at the pixels the app shipped (enlarging first gains nothing); a vector at a size that
        // needn't be enlarged at all. (Resources keep a bitmap at its own size and scale it on drawing.)
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        float aspect = iw > 0 && ih > 0 ? (float) iw / ih : 16f / 9f;
        int bitmap = bitmapWidth(art);
        int w = Math.max(64, Math.min(bitmap > 0 ? bitmap : 640, 720)); // plenty for a LOGO_SIZE logo
        int h = Math.max(36, Math.round(w / aspect));
        Bitmap work = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        art.setBounds(0, 0, w, h);
        art.draw(new Canvas(work));
        int[] px = new int[w * h];
        work.getPixels(px, 0, w, 0, 0, w, h);
        float maxCanvas = w * (bitmap > 0 ? 1.5f : 1f); // as sharp as the icon

        int bg = keyBackground(px, w, h);
        Glyph glyph = bg != BUSY ? Glyph.of(px, w, h, 0.6f, false) : null;
        if (glyph != null) {
            work.setPixels(px, 0, w, 0, 0, w, h);
            boolean own = bg != NO_BACKGROUND;
            int brand = own ? bg : brandColor(art);
            boolean pale = own ? luminance(bg) > 0.6f : glyph.light < 0.25f;
            Bitmap out = logoScene(new BitmapDrawable(res, work), glyph, LOGO_SIZE, maxCanvas, 0f, brand, pale);
            work.recycle();
            return out;
        }

        // A picture: round its corners and show it as a card over its own colour.
        Bitmap card = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(work, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = h * 0.08f;
        new Canvas(card).drawRoundRect(0, 0, w, h, r, r, p);
        work.recycle();
        Glyph whole = new Glyph(new RectF(0, 0, 1, 1), aspect, 0.5f);
        Bitmap out = logoScene(new BitmapDrawable(res, card), whole, 300f, maxCanvas, 0f, brandColor(art), false);
        card.recycle();
        return out;
    }

    /** {@link #keyBackground} results that aren't a colour: art without a background of its own, and art whose edges are too busy to be one. */
    private static final int NO_BACKGROUND = 0, BUSY = 1;

    /**
     * Makes the art's background transparent so its logo can float. The background is read from the
     * art's edges (flat, or a smooth gradient between them), and each pixel becomes the most
     * transparent colour that over that background gives the original (GIMP's "colour to alpha"),
     * so anti-aliased edges keep no fringe of the old background. Returns the background's colour
     * behind the middle of the art, {@link #NO_BACKGROUND} if it's already transparent around its
     * logo (left as it is), or {@link #BUSY} if its edges are too busy to be a background.
     */
    private static int keyBackground(int[] px, int w, int h) {
        int[][] edges = new int[4][];
        for (int side = 0; side < 4; side++) {
            edges[side] = edge(px, w, h, side);
            if (edges[side] == null) return NO_BACKGROUND;
            if (edges[side].length == 0) return BUSY;
        }
        int[] top = edges[0], bottom = edges[1], left = edges[2], right = edges[3];
        int tl = mix(top[0], left[0]), tr = mix(top[w - 1], right[0]);
        int bl = mix(bottom[0], left[h - 1]), br = mix(bottom[w - 1], right[h - 1]);

        // First guess: a Coons patch through the four edges, exact for flat and linear backgrounds.
        int[] bg = new int[w * h];
        for (int y = 0; y < h; y++) {
            float v = h > 1 ? y / (h - 1f) : 0f;
            for (int x = 0; x < w; x++) {
                float u = w > 1 ? x / (w - 1f) : 0f;
                int c = 0xFF000000;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    float bc = (1 - v) * ch(top[x], shift) + v * ch(bottom[x], shift)
                            + (1 - u) * ch(left[y], shift) + u * ch(right[y], shift)
                            - ((1 - u) * (1 - v) * ch(tl, shift) + u * (1 - v) * ch(tr, shift)
                            + (1 - u) * v * ch(bl, shift) + u * v * ch(br, shift));
                    c |= Math.round(Math.max(0f, Math.min(255f, bc))) << shift;
                }
                bg[y * w + x] = c;
            }
        }
        // Then follow radial and uneven gradients too: re-estimate it from the pixels that look like
        // background, first loosely, then strictly.
        refine(px, bg, w, h, 40);
        refine(px, bg, w, h, 16);

        for (int i = 0; i < px.length; i++) px[i] = unblend(px[i], bg[i]);
        return bg[(h / 2) * w + w / 2];
    }

    /**
     * Re-estimates the background {@code bg} as the average of the pixels within {@code near} of it,
     * over a coarse grid (cells the logo covers take their neighbours' colour), smoothly interpolated.
     */
    private static void refine(int[] px, int[] bg, int w, int h, int near) {
        int gx = 16, gy = Math.max(2, Math.round(16f * h / w)), cells = gx * gy;
        float[] r = new float[cells], g = new float[cells], b = new float[cells];
        int[] n = new int[cells];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, p = px[i];
                if (p >>> 24 < 200 || difference(p, bg[i]) > near) continue;
                int k = (y * gy / h) * gx + x * gx / w;
                r[k] += ch(p, 16);
                g[k] += ch(p, 8);
                b[k] += ch(p, 0);
                n[k]++;
            }
        }
        int cellArea = Math.max(1, (w / gx) * (h / gy)), valid = 0;
        int[] grid = new int[cells];
        for (int k = 0; k < cells; k++) {
            if (n[k] < cellArea * 0.15f) continue;
            grid[k] = 0xFF000000 | Math.round(r[k] / n[k]) << 16 | Math.round(g[k] / n[k]) << 8 | Math.round(b[k] / n[k]);
            valid++;
        }
        if (valid == 0) return;
        while (valid < cells) { // fill the gaps from their neighbours
            int[] next = grid.clone();
            int before = valid;
            for (int k = 0; k < cells; k++) {
                if (grid[k] != 0) continue;
                int cx = k % gx, cy = k / gx, sr = 0, sg = 0, sb = 0, m = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= gx || ny >= gy || grid[ny * gx + nx] == 0) continue;
                        int c = grid[ny * gx + nx];
                        sr += ch(c, 16);
                        sg += ch(c, 8);
                        sb += ch(c, 0);
                        m++;
                    }
                }
                if (m > 0) {
                    next[k] = 0xFF000000 | (sr / m) << 16 | (sg / m) << 8 | sb / m;
                    valid++;
                }
            }
            grid = next;
            if (valid == before) break;
        }
        for (int y = 0; y < h; y++) {
            float fy = Math.max(0f, Math.min(gy - 1f, (y + 0.5f) * gy / h - 0.5f));
            int y0 = (int) fy, y1 = Math.min(gy - 1, y0 + 1);
            float ty = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = Math.max(0f, Math.min(gx - 1f, (x + 0.5f) * gx / w - 0.5f));
                int x0 = (int) fx, x1 = Math.min(gx - 1, x0 + 1);
                float tx = fx - x0;
                int c00 = grid[y0 * gx + x0], c10 = grid[y0 * gx + x1], c01 = grid[y1 * gx + x0], c11 = grid[y1 * gx + x1];
                int c = 0xFF000000;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    float top = ch(c00, shift) * (1 - tx) + ch(c10, shift) * tx;
                    float bottom = ch(c01, shift) * (1 - tx) + ch(c11, shift) * tx;
                    c |= Math.round(top * (1 - ty) + bottom * ty) << shift;
                }
                bg[y * w + x] = c;
            }
        }
    }

    /**
     * The most transparent colour that over {@code b} gives {@code p}. Differences within the
     * background's own grain and compression noise stay fully transparent.
     */
    private static int unblend(int p, int b) {
        int diff = difference(p, b);
        if (p >>> 24 == 0 || diff <= 16) return 0;
        float alpha = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float d = ch(p, shift) - ch(b, shift), room = d > 0 ? 255f - ch(b, shift) : ch(b, shift);
            alpha = Math.max(alpha, room > 0 ? Math.abs(d) / room : 1f);
        }
        alpha = Math.min(1f, alpha * Math.min(1f, (diff - 16) / 16f)); // fade in just above the noise
        if (alpha <= 0.02f) return 0;
        int out = Math.round(alpha * (p >>> 24)) << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float f = ch(b, shift) + (ch(p, shift) - ch(b, shift)) / alpha;
            out |= Math.round(Math.max(0f, Math.min(255f, f))) << shift;
        }
        return out;
    }

    /** The largest difference between two colours in any channel. */
    private static int difference(int a, int b) {
        return Math.max(Math.abs(ch(a, 16) - ch(b, 16)), Math.max(Math.abs(ch(a, 8) - ch(b, 8)), Math.abs(ch(a, 0) - ch(b, 0))));
    }

    /**
     * One edge of the art's background, smoothed (side 0 top, 1 bottom, 2 left, 3 right): from each
     * pixel along it, the first opaque colour going inward, as medians over short runs so a logo
     * touching the edge doesn't count. Null if the art is mostly transparent there; empty if the
     * edge varies too much to be a background.
     */
    private static int[] edge(int[] px, int w, int h, int side) {
        int n = side < 2 ? w : h, depth = Math.max(1, (side < 2 ? h : w) / 4);
        int[] raw = new int[n];
        int found = 0;
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < depth; d++) {
                int x = side == 2 ? d : side == 3 ? w - 1 - d : i;
                int y = side == 0 ? d : side == 1 ? h - 1 - d : i;
                int p = px[y * w + x];
                if (p >>> 24 >= 200) {
                    raw[i] = p | 0xFF000000;
                    found++;
                    break;
                }
            }
        }
        if (found < n * 0.6f) return null;

        // Medians over 16 runs, then straight lines between their centres.
        int runs = Math.min(16, n);
        int[] median = new int[runs];
        int[] values = new int[n];
        for (int r = 0; r < runs; r++) {
            int from = r * n / runs, to = (r + 1) * n / runs, m = 0;
            int color = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                m = 0;
                for (int i = from; i < to; i++) if (raw[i] != 0) values[m++] = ch(raw[i], shift);
                if (m == 0) break;
                Arrays.sort(values, 0, m);
                color |= values[m / 2] << shift;
            }
            median[r] = m == 0 ? 0 : 0xFF000000 | color;
        }
        for (int r = 0; r < runs; r++) { // runs with no opaque pixels take a neighbour's colour
            if (median[r] != 0) continue;
            for (int k = 1; k < runs && median[r] == 0; k++) {
                if (r - k >= 0 && median[r - k] != 0) median[r] = median[r - k];
                else if (r + k < runs && median[r + k] != 0) median[r] = median[r + k];
            }
        }
        int[] smooth = new int[n];
        float deviation = 0;
        for (int i = 0; i < n; i++) {
            float at = (i + 0.5f) * runs / n - 0.5f;
            int r0 = Math.max(0, Math.min(runs - 1, (int) Math.floor(at))), r1 = Math.min(runs - 1, r0 + 1);
            float t = Math.max(0f, Math.min(1f, at - r0));
            int c = 0xFF000000;
            for (int shift = 16; shift >= 0; shift -= 8) {
                c |= Math.round(ch(median[r0], shift) * (1 - t) + ch(median[r1], shift) * t) << shift;
            }
            smooth[i] = c;
            if (raw[i] != 0) {
                for (int shift = 16; shift >= 0; shift -= 8) deviation += Math.abs(ch(raw[i], shift) - ch(c, shift));
            }
        }
        // Mean difference from the smoothed edge, per channel: a photo's edge is far from smooth.
        return deviation / (3f * found) > 14f ? new int[0] : smooth;
    }

    private static int ch(int color, int shift) {
        return (color >> shift) & 255;
    }

    private static int mix(int a, int b) {
        return 0xFF000000 | ((ch(a, 16) + ch(b, 16)) / 2) << 16 | ((ch(a, 8) + ch(b, 8)) / 2) << 8 | (ch(a, 0) + ch(b, 0)) / 2;
    }

    /**
     * A logo (transparent around it) large and centred above the dock, with a soft shadow, over a
     * field lit in the brand colour. Sized by its visible pixels, not its padded canvas: the area of
     * a {@code size}-unit square for any shape, within the space above the dock, with the canvas at most
     * {@code maxCanvas} pixels wide so a bitmap isn't enlarged too far. Null if that leaves the logo
     * smaller than {@code minFill} of the size it should be.
     */
    private Bitmap logoScene(Drawable fg, Glyph glyph, float size, float maxCanvas, float minFill, int brand, boolean pale) {
        float s = width / 1920f, cx = width / 2f, cy = 370f * s;
        RectF g = glyph.bounds;
        float aspect = g.width() / g.height() * glyph.aspect; // the logo's own shape, in pixels
        float want = Math.min(size * s * (float) Math.sqrt(aspect), Math.min(700f * s, size * s * aspect));
        float canvasW = Math.min(want / g.width(), maxCanvas), canvasH = canvasW / glyph.aspect;
        float lw = g.width() * canvasW, lh = g.height() * canvasH;
        if (lw < minFill * want) return null;

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // The field is all soft gradients: drawn at a quarter size and enlarged, it's the same and far quicker.
        Bitmap field = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888);
        brandField(new Canvas(field), field.getWidth(), field.getHeight(), brand, pale, glyph.light, cx / 4f, cy / 4f, s / 4f);
        c.drawBitmap(field, null, new RectF(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        field.recycle();
        int pad = 4;
        Bitmap logo = Bitmap.createBitmap((int) Math.ceil(lw) + 2 * pad, (int) Math.ceil(lh) + 2 * pad, Bitmap.Config.ARGB_8888);
        int fx = Math.round(pad - g.left * canvasW), fy = Math.round(pad - g.top * canvasH);
        fg.setBounds(fx, fy, fx + Math.round(canvasW), fy + Math.round(canvasH));
        fg.draw(new Canvas(logo));
        int lx = Math.round(cx - lw / 2f) - pad, ly = Math.round(cy - lh / 2f) - pad;
        Paint soft = new Paint();
        soft.setMaskFilter(new BlurMaskFilter(30f * s, BlurMaskFilter.Blur.NORMAL));
        int[] at = new int[2];
        Bitmap shadow = logo.extractAlpha(soft, at);
        Paint ink = new Paint(Paint.FILTER_BITMAP_FLAG);
        ink.setColor(pale ? 0x2E000000 : 0x59000000);
        c.drawBitmap(shadow, lx + at[0], ly + at[1] + 16f * s, ink);
        shadow.recycle();
        c.drawBitmap(logo, lx, ly, null);
        logo.recycle();
        return out;
    }

    /**
     * Where a logo sits on its canvas, and how bright it is.
     *
     * @param bounds The visible pixels' bounds, as fractions of the canvas.
     * @param aspect The canvas's width over its height.
     * @param light  The visible pixels' mean luminance, 0–1.
     */
    private record Glyph(RectF bounds, float aspect, float light) {
        private static final int N = 216; // measuring size for icons

        /**
         * An icon layer's logo, or null unless it's a stand-alone one covering at most {@code maxCover} of the canvas.
         */
        static Glyph measure(Drawable fg, float maxCover) {
            Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
            fg.setBounds(0, 0, N, N);
            fg.draw(new Canvas(b));
            int[] px = new int[N * N];
            b.getPixels(px, 0, N, 0, 0, N, N);
            b.recycle();
            return of(px, N, N, maxCover, true);
        }

        /**
         * The logo in w×h pixels, or null if there's nothing there, or it covers more than
         * {@code maxCover} (a whole picture). With {@code noCards}, a large solid shape filling its
         * bounds is refused too: a logo with its own background.
         */
        static Glyph of(int[] px, int w, int h, float maxCover, boolean noCards) {
            int minX = w, minY = h, maxX = -1, maxY = -1, count = 0;
            double lum = 0, weight = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = px[y * w + x], a = p >>> 24;
                    if (a < 40) continue;
                    count++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    lum += a * luminance(p);
                    weight += a;
                }
            }
            float cover = count / (float) (w * h);
            if (cover < 0.004f || cover > maxCover) return null;
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (noCards && count > 0.9f * bw * bh && bw > 0.4f * w && bh > 0.4f * h) return null;
            // Leave out stray specks at the far edges: the few outermost visible pixels on each side.
            int[] cols = new int[w], rows = new int[h];
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (px[y * w + x] >>> 24 < 40) continue;
                    cols[x]++;
                    rows[y]++;
                }
            }
            int stray = Math.round(count * 0.003f);
            for (int sum = cols[minX]; sum <= stray && minX < maxX; ) sum += cols[++minX];
            for (int sum = cols[maxX]; sum <= stray && maxX > minX; ) sum += cols[--maxX];
            for (int sum = rows[minY]; sum <= stray && minY < maxY; ) sum += rows[++minY];
            for (int sum = rows[maxY]; sum <= stray && maxY > minY; ) sum += rows[--maxY];
            RectF bounds = new RectF(minX / (float) w, minY / (float) h, (maxX + 1) / (float) w, (maxY + 1) / (float) h);
            return new Glyph(bounds, w / (float) h, (float) (lum / weight));
        }
    }

    /** The pixel width of the bitmap behind a drawable, or 0 if it's a vector (sharp at any size). */
    private static int bitmapWidth(Drawable d) {
        while (d instanceof DrawableWrapper) d = ((DrawableWrapper) d).getDrawable();
        return d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap().getWidth() : 0;
    }


    /**
     * The colour a brand reads as: vivid pixels grouped by hue, each weighted by how vivid it is, and
     * the best-represented hue averaged. Art with too little colour (white on black, say) gives grey.
     */
    private static int brandColor(Drawable d) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        int w = 64, h = iw > 0 && ih > 0 && iw > ih * 1.2f ? 36 : 64;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        coverDraw(d, new Canvas(b), w, h);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        final int bins = 24;
        float[] weight = new float[bins], red = new float[bins], green = new float[bins], blue = new float[bins];
        float[] hsv = new float[3];
        int opaque = 0;
        for (int p : px) {
            if (p >>> 24 < 128) continue;
            opaque++;
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.2f || hsv[2] < 0.18f) continue; // greys, black and white
            int bin = (int) (hsv[0] / 360f * bins) % bins;
            float vivid = hsv[1] * hsv[2];
            weight[bin] += vivid;
            red[bin] += vivid * ((p >> 16) & 255);
            green[bin] += vivid * ((p >> 8) & 255);
            blue[bin] += vivid * (p & 255);
        }
        int best = -1;
        float bestWeight = 0;
        for (int i = 0; i < bins; i++) {
            float sum = weight[(i + bins - 1) % bins] + weight[i] + weight[(i + 1) % bins];
            if (sum > bestWeight) {
                bestWeight = sum;
                best = i;
            }
        }
        if (best < 0 || bestWeight < 0.03f * opaque) return 0xFF808080;
        float r = 0, g = 0, bl = 0;
        for (int i = best - 1; i <= best + 1; i++) {
            int k = (i + bins) % bins;
            r += red[k];
            g += green[k];
            bl += blue[k];
        }
        return Color.rgb(Math.round(r / bestWeight), Math.round(g / bestWeight), Math.round(bl / bestWeight));
    }

    /**
     * A field in the brand's colour, brightest behind the logo like light falling on it: deep for a
     * light logo, pale for a dark one, grey for a brand without a colour.
     */
    private static void brandField(Canvas c, int w, int h, int brand, boolean pale, float logoLight, float cx, float cy, float s) {
        float[] hsv = new float[3];
        Color.colorToHSV(brand, hsv);
        float hue = hsv[0], sat = Math.min(hsv[1], 0.85f);
        // Keep the glow well below the logo's own brightness so the logo stands out from it.
        float glow = pale ? 0.98f : sat < 0.12f ? 0.34f : 0.62f;
        while (!pale && glow > 0.28f && luminance(Color.HSVToColor(new float[]{hue, sat * 0.9f, glow})) > logoLight - 0.4f) {
            glow -= 0.02f;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setDither(true);
        int top = Color.HSVToColor(new float[]{hue, sat, pale ? 0.80f : 0.17f});
        int bottom = Color.HSVToColor(new float[]{hue, sat, pale ? 0.60f : 0.05f});
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawPaint(p);
        int bloom = Color.HSVToColor(new float[]{hue, sat * (pale ? 0.6f : 0.9f), glow}) & 0x00FFFFFF;
        int[] falloff = {bloom | 0xE0000000, bloom | 0x80000000, bloom | 0x26000000, bloom};
        p.setShader(new RadialGradient(cx, cy, 1050f * s, falloff, new float[]{0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawPaint(p);
    }

    private static float luminance(int p) {
        return (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
    }

    /**
     * Moves a finished full-screen image into GPU memory: one copy instead of a CPU copy plus its
     * texture, and the upload happens here on the IO thread rather than in the first frame.
     */
    private static Bitmap toHardware(Bitmap software) {
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        if (hardware == null) return software;
        software.recycle();
        return hardware;
    }

    /**
     * The app's launch screen (its launch theme's windowBackground), screen-sized, when that's a real
     * picture of about 720p or more. Most TV apps launch on a flat colour or a small logo on black,
     * which make a worse backdrop than the logo field, so those return null.
     */
    private Bitmap splash(Resources res, ActivityInfo ai) {
        int themeId = ai.getThemeResource();
        if (themeId == 0) return null;
        try {
            Resources.Theme theme = res.newTheme();
            theme.applyStyle(themeId, true);
            TypedValue value = new TypedValue();
            if (!theme.resolveAttribute(android.R.attr.windowBackground, value, true) || value.resourceId == 0) return null;
            Drawable d = res.getDrawable(value.resourceId, theme);
            if (!(d instanceof BitmapDrawable) || d.getIntrinsicWidth() < 1216 || d.getIntrinsicHeight() < 684) return null;
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            coverDraw(d, new Canvas(out), width, height);
            return out;
        } catch (RuntimeException e) {
            return null; // another app's resources: anything can go wrong, and the logo still works
        }
    }

    /** Draws {@code d} scaled to cover w×h, centred. */
    private static void coverDraw(Drawable d, Canvas c, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h);
        } else {
            float k = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * k), dh = Math.round(ih * k);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        }
        d.draw(c);
    }

    /**
     * The cached copy of a recommendation image, downloading it first if needed. Only HTTPS (no
     * cleartext, no cookies, size-capped), or content:// / android.resource:// URIs belonging to the
     * app that published the recommendation (so it can't make us read another app's data).
     */
    private File fetch(String url, String owner) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        boolean https = scheme.equalsIgnoreCase("https");
        boolean local = scheme.equals(ContentResolver.SCHEME_CONTENT) || scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE);
        // Checked before the cache too: another app naming the same URI mustn't get it from there.
        if (local && !ownedBy(uri, scheme, owner)) return null;
        // An app without network access can't use us to reach the network for it.
        if (https && (owner == null || pm.checkPermission(Manifest.permission.INTERNET, owner) != PackageManager.PERMISSION_GRANTED)) {
            return null;
        }
        File out = cacheFile(url);
        if (out.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            out.setLastModified(System.currentTimeMillis()); // recently used, for the cache trim
            return out;
        }
        HttpURLConnection http = null;
        File tmp = null;
        try {
            InputStream in;
            if (https) {
                http = (HttpURLConnection) new URL(url).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(15000);
                http.setUseCaches(false);
                http.setRequestProperty("Accept", "image/*");
                if (http.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                in = http.getInputStream();
            } else if (local) {
                in = resolver.openInputStream(uri);
                if (in == null) return null;
            } else {
                return null;
            }
            try (InputStream src = in) {
                //noinspection ResultOfMethodCallIgnored
                downloads.mkdirs();
                tmp = File.createTempFile("part", null, downloads);
                if (!copy(src, tmp)) return null;
            }
            return tmp.renameTo(out) ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
            if (tmp != null && tmp.exists()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    private boolean ownedBy(Uri uri, String scheme, String owner) {
        String authority = uri.getAuthority();
        if (authority == null || owner == null) return false;
        if (scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) return authority.equals(owner);
        ProviderInfo provider = pm.resolveContentProvider(authority, 0);
        return provider != null && provider.exported && owner.equals(provider.packageName);
    }

    /** Copies at most MAX_DOWNLOAD bytes; false if the source is bigger than that. */
    private static boolean copy(InputStream src, File to) throws IOException {
        try (OutputStream dst = new FileOutputStream(to)) {
            byte[] buf = new byte[16 << 10];
            int total = 0;
            for (int n; (n = src.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_DOWNLOAD) return false;
                dst.write(buf, 0, n);
            }
            return true;
        }
    }

    private File cacheFile(String url) {
        return new File(downloads, sha1(url));
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Scales and centre-crops {@code src} to exactly w×h. */
    private static Bitmap cover(Bitmap src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float s = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** A heavily blurred 192px-wide copy. Drawn scaled up with bilinear filtering it reads as a smooth blur. */
    static Bitmap blur(Bitmap src) {
        int w = 192;
        int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
        // Two-step downscale so the bilinear sampling doesn't skip detail and sparkle.
        Bitmap mid = Bitmap.createScaledBitmap(src, w * 3, h * 3, true);
        Bitmap small = Bitmap.createScaledBitmap(mid, w, h, true);
        if (mid != src && mid != small) mid.recycle();
        if (small == src) small = src.copy(Bitmap.Config.ARGB_8888, true);

        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlurTransposed(px, tmp, w, h);
            boxBlurTransposed(tmp, px, h, w);
        }
        Bitmap out = small.isMutable() ? small : Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static final int BLUR_RADIUS = 3;

    /** Box-blurs each row of {@code src} (w×h) by {@link #BLUR_RADIUS}, writing the result transposed into {@code dst} (h×w). */
    private static void boxBlurTransposed(int[] src, int[] dst, int w, int h) {
        int r = BLUR_RADIUS, div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[row + Math.min(Math.max(i, 0), w - 1)];
                sa += p >>> 24;
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = (sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int out = src[row + Math.max(x - r, 0)];
                int in = src[row + Math.min(x + r + 1, w - 1)];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
    }

    /** The built-in wallpaper: soft blue / violet / teal light over near-black, in the spirit of tvOS. */
    private static Bitmap aurora() {
        int w = 640, h = 360; // small: it's blurred and drawn full screen anyway
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setDither(true);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF0B1024, 0xFF040509, Shader.TileMode.CLAMP));
        c.drawPaint(p);
        glow(c, p, w * 0.18f, h * 0.20f, w * 0.62f, 0xC02A4FD6);
        glow(c, p, w * 0.84f, h * 0.26f, w * 0.52f, 0x997B3FC4);
        glow(c, p, w * 0.52f, h * 1.00f, w * 0.58f, 0x7A0E8A9A);
        glow(c, p, w * 1.02f, h * 1.02f, w * 0.40f, 0x59B0306E);
        return b;
    }

    private static void glow(Canvas c, Paint p, float x, float y, float r, int color) {
        p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawPaint(p);
    }
}
