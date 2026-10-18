package app.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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

/**
 * Full-screen backgrounds. Behind the dock, for the focused app, in order of preference:
 *
 *   files/topshelf/<package>.jpg     your own image (push with adb)
 *   the app's TV recommendation      see {@link Recommendations}, downloaded once and cached;
 *                                    under 720p it's shown as a card with its title instead
 *   files/wallpaper.jpg              if you've pushed one
 *   the app's own logo               large, over a field of its colours, as tvOS does
 *
 * A built-in aurora covers the moment before apps load. Each backdrop is kept sharp at screen size
 * plus a tiny blurred copy for the glass and the grid's blur.
 */
final class Backdrops {

    static final class Backdrop {
        final Bitmap sharp;
        final Bitmap blur;
        final BitmapShader blurShader;
        /** Set when the image is an app's recommendation. */
        final Recommendations.Item program;
        /** Brightness (0–1) behind the clock, the full-screen hint and the Control Center, so text can flip to dark. */
        final float clockLight, hintLight, controlsLight;

        Backdrop(Bitmap sharp, Bitmap blur, Recommendations.Item program) {
            this.sharp = sharp;
            this.blur = blur;
            this.blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.program = program;
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
    private static final String REMOTE = "rec:", APP = "app:";
    private static final String[] EXTENSIONS = {"jpg", "jpeg", "png", "webp"};
    private static final int MAX_DOWNLOAD = 16 << 20;
    private static final int MAX_CACHED = 48;

    private final ContentResolver resolver;
    private final PackageManager pm;
    private final File dir, downloads;
    private final int width, height;
    private final Backdrop fallback;

    // Written on the IO thread, read on the UI thread.
    private volatile Map<String, String> userShelves = Collections.emptyMap();
    private volatile Map<String, Recommendations.Item> recommended = Collections.emptyMap();
    private volatile Set<String> downloaded = Collections.emptySet();
    private volatile String wallpaper = DEFAULT;

    // Network thread only: when each URL last failed, so a dead network isn't hammered.
    private final Map<String, Long> failedAt = new HashMap<>();
    private static final long RETRY_MS = 15 * 60_000L;

    // UI thread only. Screen-size bitmaps are ~8 MB each, so keep just a few.
    private final Map<String, Backdrop> cache = new LinkedHashMap<String, Backdrop>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Backdrop> eldest) {
            return size() > 6;
        }
    };

    Backdrops(Context context, int width, int height) {
        File external = context.getExternalFilesDir(null);
        this.resolver = context.getContentResolver();
        this.pm = context.getPackageManager();
        this.dir = external != null ? external : context.getFilesDir();
        this.downloads = new File(context.getCacheDir(), "shelf");
        this.width = width;
        this.height = height;
        Bitmap aurora = aurora(640, 360);
        this.fallback = new Backdrop(aurora, blur(aurora), null);
    }

    /** Re-reads local images and app recommendations. Keys include mtimes/URLs so changes are picked up. IO thread. */
    void scan(Context context) {
        Map<String, String> found = new HashMap<>();
        File[] files = new File(dir, "topshelf").listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                if (dot > 0 && isImage(name.substring(dot + 1)) && f.isFile()) found.put(name.substring(0, dot), key(f));
            }
        }
        userShelves = found;
        Map<String, Recommendations.Item> recs = Recommendations.load(context);
        Set<String> ready = new HashSet<>();
        for (Recommendations.Item item : recs.values()) if (cacheFile(item.image).isFile()) ready.add(item.image);
        downloaded = ready;
        recommended = recs;
        String wp = DEFAULT;
        for (String ext : EXTENSIONS) {
            File f = new File(dir, "wallpaper." + ext);
            if (f.isFile()) {
                wp = key(f);
                break;
            }
        }
        wallpaper = wp;
    }

    /**
     * Downloads recommendation images that aren't cached yet, then trims the cache. A shelf only
     * appears once its image is on disk, so {@code onReady} runs after each new download. Network thread.
     */
    void prefetch(Runnable onReady) {
        for (Recommendations.Item item : recommended.values()) {
            String url = item.image;
            if (downloaded.contains(url)) continue;
            Long failed = failedAt.get(url);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) continue;
            if (fetch(url, item.pkg) != null) {
                Set<String> ready = new HashSet<>(downloaded);
                ready.add(url);
                downloaded = ready;
                onReady.run();
            } else {
                failedAt.put(url, System.currentTimeMillis());
            }
        }
        File[] cached = downloads.listFiles();
        if (cached != null && cached.length > MAX_CACHED) {
            Arrays.sort(cached, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < cached.length - MAX_CACHED; i++) //noinspection ResultOfMethodCallIgnored
                cached[i].delete();
        }
    }

    String shelfKey(String pkg) {
        String own = userShelves.get(pkg);
        if (own != null) return own;
        Recommendations.Item item = recommended.get(pkg);
        return item != null && downloaded.contains(item.image) ? REMOTE + item.image : null;
    }

    /** What sits behind the dock for an app: its top shelf, else your wallpaper, else its logo. */
    String backdropKey(String pkg) {
        String shelf = shelfKey(pkg);
        if (shelf != null) return shelf;
        return DEFAULT.equals(wallpaper) ? APP + pkg : wallpaper;
    }

    Recommendations.Item recommendation(String pkg) {
        return recommended.get(pkg);
    }

    String wallpaperKey() {
        return wallpaper;
    }

    Backdrop cached(String key) {
        return DEFAULT.equals(key) ? fallback : cache.get(key);
    }

    void put(String key, Backdrop b) {
        cache.put(key, b);
    }

    /** Prepares the backdrop behind {@code key} (images are already on disk), or null. IO thread. */
    Backdrop load(String key, Recommendations.Item program) {
        if (DEFAULT.equals(key)) return fallback;
        if (key.startsWith(APP)) return appLogo(key.substring(APP.length()));
        boolean remote = key.startsWith(REMOTE);
        File file = remote ? cacheFile(key.substring(REMOTE.length())) : new File(key.substring(0, key.lastIndexOf('@')));
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
        Bitmap sharp = hd ? cover(src, width, height) : poster(src, remote && program != null ? program.title : null);
        if (sharp != src) src.recycle();
        Bitmap blurred = blur(sharp);
        return new Backdrop(toHardware(sharp), blurred, remote ? program : null);
    }

    /**
     * For pictures under 720p, which would look soft stretched across the screen: the picture as a
     * 16:9 card on the left (centre-cropped, which also drops letterbox bars) with the program's
     * name beside it, over a blurred, darkened copy of the picture.
     */
    private Bitmap poster(Bitmap src, String title) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = width / 1920f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new LightingColorFilter(0xFF8C8C8C, 0));
        Bitmap field = blur(src);
        c.drawBitmap(field, null, new RectF(0, 0, width, height), p);
        field.recycle();
        p.setColorFilter(null);

        RectF card = new RectF(92.5f * s, 172f * s, (92.5f + 736f) * s, (172f + 414f) * s);
        float radius = 26f * s;
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
        }
        return out;
    }

    /**
     * For apps without a top shelf, as tvOS does: the app's banner (or icon) large, over a field of
     * its own colours. Taken from the highest-density resource the app ships and enlarged at most
     * 3x, so it stays sharp; a banner's edges are feathered so the logo floats rather than sitting
     * on a card.
     */
    private Backdrop appLogo(String pkg) {
        Drawable art;
        boolean banner;
        float nativeScale = 1f; // decoded pixels per pixel the app actually shipped
        try {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            ResolveInfo ri = launch != null ? pm.resolveActivity(launch, 0) : null;
            if (ri == null) return null;
            ActivityInfo ai = ri.activityInfo;
            Resources res = pm.getResourcesForApplication(ai.applicationInfo);
            int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
            banner = id != 0;
            if (!banner) id = ai.getIconResource();
            if (id == 0) return null;
            TypedValue value = new TypedValue();
            res.getValueForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, value, true);
            if (value.density > 0 && value.density != TypedValue.DENSITY_NONE) {
                nativeScale = DisplayMetrics.DENSITY_XXXHIGH / (float) value.density;
            }
            art = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        }
        if (art == null) return null;

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = width / 1920f;

        // The field: a banner blurred down to its colours, or the icon's average colour.
        Bitmap swatch = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(swatch);
        if (banner) {
            coverDraw(art, sc, 384, 216);
        } else {
            int color = Apps.averageColor(art);
            Paint g = new Paint();
            g.setShader(new LinearGradient(0, 0, 0, 216, color, Color.rgb(Color.red(color) / 3, Color.green(color) / 3,
                    Color.blue(color) / 3), Shader.TileMode.CLAMP));
            sc.drawPaint(g);
        }
        Bitmap field = blur(swatch);
        swatch.recycle();
        c.drawBitmap(field, null, new RectF(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        field.recycle();

        // The art itself, centred above the dock.
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        float aspect = iw > 0 && ih > 0 ? (float) iw / ih : banner ? 16f / 9f : 1f;
        float w = Math.min((banner ? 980f : 380f) * s, (banner ? 420f : 380f) * s * aspect);
        if (art instanceof BitmapDrawable) w = Math.min(w, ((BitmapDrawable) art).getBitmap().getWidth() / nativeScale * 3f);
        float h = w / aspect, cx = width / 2f, cy = 370f * s;
        art.setBounds(Math.round(cx - w / 2f), Math.round(cy - h / 2f), Math.round(cx + w / 2f), Math.round(cy + h / 2f));
        if (banner) {
            RectF dst = new RectF(art.getBounds());
            int layer = c.saveLayer(dst, null);
            art.draw(c);
            Paint mask = new Paint();
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            int[] fade = {0x00000000, 0xFF000000, 0xFF000000, 0x00000000};
            float[] stops = {0f, 0.14f, 0.86f, 1f};
            mask.setShader(new LinearGradient(dst.left, 0, dst.right, 0, fade, stops, Shader.TileMode.CLAMP));
            c.drawRect(dst, mask);
            mask.setShader(new LinearGradient(0, dst.top, 0, dst.bottom, fade, stops, Shader.TileMode.CLAMP));
            c.drawRect(dst, mask);
            c.restoreToCount(layer);
        } else {
            art.draw(c);
        }
        Bitmap blurred = blur(out);
        return new Backdrop(toHardware(out), blurred, null);
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
        File out = cacheFile(url);
        if (out.isFile()) return out;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        HttpURLConnection http = null;
        File tmp = null;
        try {
            InputStream in;
            if (scheme.equalsIgnoreCase("https")) {
                http = (HttpURLConnection) new URL(url).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(15000);
                http.setUseCaches(false);
                http.setRequestProperty("Accept", "image/*");
                if (http.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                in = http.getInputStream();
            } else if (scheme.equals(ContentResolver.SCHEME_CONTENT) || scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) {
                if (!ownedBy(uri, scheme, owner)) return null;
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

    private static String key(File f) {
        return f.getPath() + '@' + f.lastModified();
    }

    private static boolean isImage(String ext) {
        for (String e : EXTENSIONS) if (e.equalsIgnoreCase(ext)) return true;
        return false;
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
            boxBlurTransposed(px, tmp, w, h, 3);
            boxBlurTransposed(tmp, px, h, w, 3);
        }
        Bitmap out = small.isMutable() ? small : Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** Box-blurs each row of {@code src} (w×h) with radius r, writing the result transposed into {@code dst} (h×w). */
    private static void boxBlurTransposed(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
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
    private static Bitmap aurora(int w, int h) {
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
