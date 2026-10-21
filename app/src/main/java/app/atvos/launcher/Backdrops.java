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
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.net.Uri;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Full-screen backgrounds. The backdrop for the focused app in the dock is, in order of priority:
 * <pre>
 *   the TV recommendation of the app   see {@link Recommendations}, downloaded one time and cached;
 *                                      below Full HD, a row of cards with the programs of the app
 *   the splash image of the app        when its launch screen is a picture of 720p or more
 *   the logo of the app                large: the art of its icon, or the logo of its banner cut
 *                                      from its background, over a glow in its colour
 * </pre>
 * A built-in aurora shows before the apps load. Each backdrop has a sharp picture at screen size and a small blurred
 * copy for the glass and the grid.
 */
final class Backdrops {

    static final class Backdrop {
        final Bitmap sharp;
        final Bitmap blur;
        final BitmapShader blurShader;
        /** The pixels of the blur. The glass mixes the pixels of two backdrops on the CPU during a crossfade. */
        final int[] blurPixels;
        /** True if the picture contains the details scrim of the full-screen shelf. */
        boolean details;

        /** The recommendation of the app that the image shows, or null. */
        final Recommendations.Item program;
        /**
         * Below Full HD: the programs of the app as a row of cards over a plain background. Null for a full-screen
         * picture.
         */
        final List<Card> row;
        /**
         * The brightness (0 to 1) behind the clock and behind the Control Center. Text changes to dark above a
         * threshold.
         */
        final float clockLight, controlsLight;
        /** The colour behind the full-screen hint. It sets the tint of the hint. */
        final int hintColor;

        Backdrop(Bitmap sharp, Bitmap blur, Recommendations.Item program) {
            this(sharp, blur, program, null);
        }

        Backdrop(Bitmap sharp, Bitmap blur, Recommendations.Item program, List<Card> row) {
            this.sharp = sharp;
            this.blur = blur;
            this.blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.blurPixels = new int[blur.getWidth() * blur.getHeight()];
            blur.getPixels(blurPixels, 0, blur.getWidth(), 0, 0, blur.getWidth(), blur.getHeight());
            this.program = program;
            this.row = row;
            int bw = blur.getWidth(), bh = blur.getHeight();
            // Luminance is a weighted sum of the channels, so the luminance of the mean colour is the mean luminance.
            this.clockLight = luminance(colour(blurPixels, bw, bh, 0.86f, 0.03f, 1f, 0.11f));
            this.controlsLight = luminance(colour(blurPixels, bw, bh, 0.72f, 0.10f, 1f, 0.53f));
            this.hintColor = colour(blurPixels, bw, bh, 0.40f, 0.62f, 0.60f, 0.70f);
        }

        /** Returns the mean colour of a region of the bw×bh pixels. The region edges are fractions of the size. */
        private static int colour(int[] px, int bw, int bh, float l, float t, float r, float bottom) {
            int x0 = Math.min((int) (l * bw), bw - 1), x1 = Math.min(Math.max(x0 + 1, (int) (r * bw)), bw);
            int y0 = Math.min((int) (t * bh), bh - 1), y1 = Math.min(Math.max(y0 + 1, (int) (bottom * bh)), bh);
            int n = (x1 - x0) * (y1 - y0);
            long red = 0, green = 0, blue = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int p = px[y * bw + x];
                    red += (p >> 16) & 255;
                    green += (p >> 8) & 255;
                    blue += p & 255;
                }
            }
            return Color.rgb((int) (red / n), (int) (green / n), (int) (blue / n));
        }
    }

    static final String DEFAULT = "default";
    /**
     * One card in a row: its program, its picture and its width in design units. The picture is cropped to the card
     * (16:9, or square for square art) at the card size, with round corners. The home screen draws it as a plain
     * bitmap.
     */
    record Card(Recommendations.Item item, Bitmap picture, float width) {
    }

    /** The card size and the corner radius of a row, in design units (1920 wide). */
    static final float ROW_CARD_W = 840f, ROW_CARD_H = 472.5f, ROW_CARD_R = 22f;
    private static final int ROW_DARK = 0xFF1E1E1E, ROW_LIGHT = 0xFFB1B0B1; // row backgrounds: dark, light
    /** The visible logo of each app has the area of a square of this size in design units, for any shape. */
    private static final float LOGO_SIZE = 160f;
    // The packages that have each recommendation source off.
    private static final String NO_WATCH_NEXT = "no_watch_next", NO_PREVIEWS = "no_previews";
    /**
     * Key prefixes. REMOTE: a recommendation behind the dock, with the dock scrim. FULL: the same picture full screen,
     * with the details scrim. APP: the logo of an app. ROW: a row.
     */
    private static final String REMOTE = "rec:", FULL = "full:", APP = "app:", ROW = "row:";
    // The scrims behind the dock and the clock, as fractions of the screen height. They are drawn into each backdrop
    // when the backdrop is made.
    private static final float SCRIM_BOTTOM_FROM = 0.45f;
    private static final int SCRIM_BOTTOM = 0x8C000000, SCRIM_TOP = 0x4D000000;
    private static final float SCRIM_TOP_DESIGN = 220f; // the height of the top scrim in design units (1920 wide)
    private static final int MAX_DOWNLOAD = 16 << 20;
    /** The maximum time for one download. After this time, the download stops. */
    private static final long FETCH_DEADLINE_S = 30;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fetch-deadline");
        t.setDaemon(true);
        return t;
    });
    private static final int MAX_CACHED = 72; // some pages for each app that has a top shelf

    private final ContentResolver resolver;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final File downloads;
    private final int width, height;
    private final Backdrop fallback;

    // The best program and all pages of each app. The IO thread writes it and the UI thread reads it.
    private volatile Map<String, Recommendations.Item> recommended = Collections.emptyMap();
    private volatile Map<String, List<Recommendations.Item>> pages = Collections.emptyMap();
    // The image of the page that each app showed last, so that the dock keeps it. The UI thread writes it and all
    // threads read it.
    private final Map<String, String> chosen = new ConcurrentHashMap<>();
    // True for each image on disk that is Full HD or more (a full-screen picture), else false (a row of cards). Each
    // image is measured one time.
    private final Map<String, Boolean> hd = new ConcurrentHashMap<>();
    // The images on disk. prefetch() adds each download, and scan() makes the set agree with the disk. Both threads use
    // it.
    private final Set<String> downloaded = ConcurrentHashMap.newKeySet();
    private volatile Set<String> watchNext = Collections.emptySet(), previews = Collections.emptySet();

    // The time of the last failure of each URL, so that a failed URL is not tried again too soon. Network thread only.
    private final Map<String, Long> failedAt = new HashMap<>();
    private static final long RETRY_MS = 15 * 60_000L;

    // The number of backdrops in the memory cache. Each backdrop is approximately 8 MB at 1080p, so a low-RAM TV keeps
    // fewer. UI thread only.
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
        // The backdrops are not larger than 1080p, also on a TV with a 4K UI.
        float k = Math.min(1f, 1920f / Math.max(1, width));
        this.width = Math.max(1, Math.round(width * k));
        this.height = Math.max(1, Math.round(height * k));
        ActivityManager am = context.getSystemService(ActivityManager.class);
        this.cacheSize = am != null && am.isLowRamDevice() ? 3 : 6;
        Bitmap aurora = bake(aurora());
        Bitmap blurred = blur(aurora);
        this.fallback = new Backdrop(toHardware(aurora), blurred, null);
    }

    /**
     * Reads the recommendations of the apps again. The keys contain the image URL, so a new image gets a new key. IO
     * thread.
     */
    void scan(Context context) {
        Recommendations.Found found = Recommendations.load(context, off(NO_WATCH_NEXT), off(NO_PREVIEWS));
        // The cache key is the address only. This removes the images that an app cannot show, so that a different app
        // cannot get an image from the cache.
        Map<String, List<Recommendations.Item>> shown = new HashMap<>();
        Map<String, Recommendations.Item> best = new HashMap<>();
        for (Map.Entry<String, List<Recommendations.Item>> e : found.pages.entrySet()) {
            List<Recommendations.Item> ok = new ArrayList<>();
            for (Recommendations.Item item : e.getValue()) if (mayShow(item.image(), item.pkg())) ok.add(item);
            if (ok.isEmpty()) continue;
            shown.put(e.getKey(), ok);
            best.put(e.getKey(), ok.get(0));
        }
        // Adds the images that are on disk and removes the images that are not. The set is not cleared, so that a
        // download that completes on the network thread at the same time is kept.
        for (List<Recommendations.Item> list : shown.values()) {
            for (Recommendations.Item item : list) {
                if (cacheFile(item.image()).isFile()) {
                    downloaded.add(item.image());
                    measure(item.image());
                }
            }
        }
        downloaded.removeIf(url -> !cacheFile(url).isFile());
        hd.keySet().retainAll(downloaded); // sizes only of the cached images
        watchNext = found.watchNext;
        previews = found.previews;
        pages = shown;
        recommended = best;
    }

    /**
     * Returns the recommendation sources of an app as {@link Recommendations#WATCH_NEXT} and
     * {@link Recommendations#PREVIEWS} bits: the sources that it publishes, and the sources that are off.
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

    /** Turns one source of an app on or off. The change applies at the next {@link #scan}. */
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
     * Downloads the recommendation images that are not cached, then trims the cache. A shelf shows only when its image
     * is on disk, so {@code onReady} runs after each new download. Network thread.
     */
    void prefetch(Runnable onReady) {
        for (Recommendations.Item item : recommended.values()) {
            String url = item.image();
            if (downloaded.contains(url)) continue;
            Long failed = failedAt.get(url);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) continue;
            if (fetch(url, item.pkg()) != null) {
                downloaded.add(url);
                measure(url);
                onReady.run();
            } else {
                failedAt.put(url, System.currentTimeMillis());
            }
        }
        // Keeps the images that were used last (fetch() updates the time of a file at each use). An image that a
        // current recommendation shows is never deleted.
        File[] cached = downloads.listFiles();
        if (cached != null && cached.length > MAX_CACHED) {
            Set<String> inUse = new HashSet<>();
            for (List<Recommendations.Item> list : pages.values()) {
                for (Recommendations.Item item : list) inUse.add(cacheFile(item.image()).getName());
            }
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

    /**
     * Downloads the other pages of one app for the full-screen top shelf. The first page downloads with the pages of
     * all apps. {@code onReady} runs after each download. Network thread.
     */
    void prefetchPages(String pkg, Runnable onReady) {
        List<Recommendations.Item> list = pages.get(pkg);
        if (list == null) return;
        for (Recommendations.Item item : list) {
            String url = item.image();
            if (downloaded.contains(url)) continue;
            Long failed = failedAt.get(url);
            if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) continue;
            if (fetch(url, pkg) != null) {
                downloaded.add(url);
                measure(url);
                onReady.run();
            } else {
                failedAt.put(url, System.currentTimeMillis());
            }
        }
    }

    /** Records from the image header if a downloaded image is Full HD. */
    private void measure(String url) {
        if (hd.containsKey(url)) return;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(cacheFile(url).getPath(), bounds);
        if (bounds.outWidth > 0 && bounds.outHeight > 0) hd.put(url, fullHd(bounds.outWidth, bounds.outHeight));
    }

    /** Returns the program behind the dock for an app: the page that it showed last, else its best page. */
    private Recommendations.Item current(String pkg) {
        String url = chosen.get(pkg);
        List<Recommendations.Item> list = url != null && downloaded.contains(url) ? pages.get(pkg) : null;
        if (list != null) for (Recommendations.Item item : list) if (item.image().equals(url)) return item;
        return recommended.get(pkg);
    }

    /** Returns the full-screen pages of the app that are on disk and 720p or more, best first. */
    List<Recommendations.Item> pages(String pkg) {
        List<Recommendations.Item> list = pages.get(pkg), out = new ArrayList<>();
        if (list == null) return out;
        for (Recommendations.Item item : list) {
            if (downloaded.contains(item.image()) && Boolean.TRUE.equals(hd.get(item.image()))) out.add(item);
        }
        return out;
    }

    /** Shows this page behind the dock until the pages of the app change. UI thread. */
    void choose(String pkg, Recommendations.Item item) {
        chosen.put(pkg, item.image());
    }

    /**
     * Returns true for 1080p (±5%) or larger at an aspect ratio of 4:3 or wider. A large square picture makes a square
     * card.
     */
    private static boolean fullHd(int w, int h) {
        return w >= 1824 && h >= 1026 && w * 10L >= h * 13L;
    }

    /**
     * Returns the key of the top shelf of the app: its full-screen picture if it is Full HD, else a row of its
     * programs.
     */
    private String shelfKey(String pkg) {
        Recommendations.Item item = current(pkg);
        if (item == null || !downloaded.contains(item.image())) return null;
        if (!Boolean.FALSE.equals(hd.get(item.image()))) return REMOTE + item.image();
        // The key contains the hash of the items, so new programs, progress or titles make a new row.
        return ROW + pkg + "#" + rowItems(pkg).hashCode() + (prefs.getBoolean(HomeActivity.KEY_LIGHT, false) ? "L" : "D");
    }

    /** Returns the programs of the app that are on disk, in order: the cards of a row. */
    private List<Recommendations.Item> rowItems(String pkg) {
        List<Recommendations.Item> list = pages.get(pkg), out = new ArrayList<>();
        if (list != null) for (Recommendations.Item item : list) if (downloaded.contains(item.image())) out.add(item);
        return out;
    }

    /**
     * Returns the key of the backdrop behind the dock for an app: its top shelf, else its splash picture or its logo.
     * {@code fullScreen}: the full-screen shelf, with the details scrim instead of the dock scrim.
     */
    String backdropKey(String pkg, boolean fullScreen) {
        String shelf = shelfKey(pkg);
        if (shelf == null) return APP + pkg;
        return fullScreen && shelf.startsWith(REMOTE) ? FULL + shelf.substring(REMOTE.length()) : shelf;
    }

    Recommendations.Item recommendation(String pkg) {
        return current(pkg);
    }

    Backdrop cached(String key) {
        return DEFAULT.equals(key) ? fallback : cache.get(key);
    }

    void put(String key, Backdrop b) {
        cache.put(key, b);
    }


    /**
     * Prepares the backdrop for {@code key}, or returns null. The images are on disk. Out of memory gives null. Decode
     * thread: it can run at the same time as {@link #scan} and the downloads, so it reads only their thread-safe state.
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
        if (key.startsWith(ROW)) return row(key.substring(ROW.length(), key.lastIndexOf('#')));
        boolean full = key.startsWith(FULL);
        if (!full && !key.startsWith(REMOTE)) return null;
        File file = cacheFile(key.substring((full ? FULL : REMOTE).length()));
        if (!file.isFile()) return null;

        // Reads the header first. The size selects full screen or a row.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        if (!fullHd(bounds.outWidth, bounds.outHeight)) return program != null ? row(program.pkg()) : null;

        // Halves the size while the picture is larger than the screen in both directions, and while it has more than
        // four screens of pixels.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        opts.inMutable = true; // a screen-sized picture gets its scrims without a copy
        while (bounds.outWidth / (opts.inSampleSize * 2) >= width && bounds.outHeight / (opts.inSampleSize * 2) >= height
                || (long) (bounds.outWidth / opts.inSampleSize) * (bounds.outHeight / opts.inSampleSize) > 4L * width * height) {
            opts.inSampleSize *= 2;
        }
        Bitmap src = BitmapFactory.decodeFile(file.getPath(), opts);
        if (src == null) return null;
        Bitmap sharp = cover(src, width, height);
        if (sharp != src) src.recycle();
        // Each blur includes the scrims of its picture: the dock and clock scrims, or the details scrim on the
        // full-screen shelf.
        sharp = full ? bakeDetails(sharp) : bake(sharp);
        Bitmap blurred = blur(sharp); // before toHardware, which recycles the software copy
        Backdrop b = new Backdrop(toHardware(sharp), blurred, program);
        b.details = full;
        return b;
    }

    /**
     * Makes a row backdrop for pictures below Full HD: the programs of the app as cards over a plain background. Wide
     * pictures make 16:9 cards. Square pictures make square cards of the same height. The home screen draws and focuses
     * the cards.
     */
    private Backdrop row(String pkg) {
        float s = width / 1920f;
        int ch = Math.round(ROW_CARD_H * s);
        List<Card> cards = new ArrayList<>();
        for (Recommendations.Item item : rowItems(pkg)) {
            File file = cacheFile(item.image());
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
            // Almost square or taller: a square card. Wider: a 16:9 card, cropped from 4:3 or 3:2.
            boolean square = bounds.outWidth * 4L < bounds.outHeight * 5L;
            float design = square ? ROW_CARD_H : ROW_CARD_W;
            int cw = Math.round(design * s);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= cw && bounds.outHeight / (opts.inSampleSize * 2) >= ch) {
                opts.inSampleSize *= 2;
            }
            Bitmap src = BitmapFactory.decodeFile(file.getPath(), opts);
            if (src == null) continue;
            Bitmap crop = cover(src, cw, ch); // centre-cropped to the card, which also removes letterbox bars
            if (crop != src) src.recycle();
            Bitmap card = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new BitmapShader(crop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            new Canvas(card).drawRoundRect(0, 0, cw, ch, ROW_CARD_R * s, ROW_CARD_R * s, p);
            crop.recycle();
            cards.add(new Card(item, toHardware(card), design));
        }
        if (cards.isEmpty()) return null;
        int colour = prefs.getBoolean(HomeActivity.KEY_LIGHT, false) ? ROW_LIGHT : ROW_DARK;
        // The background: a strip with the height of the screen and the scrims, stretched across the screen.
        Bitmap strip = Bitmap.createBitmap(4, height, Bitmap.Config.ARGB_8888);
        strip.eraseColor(colour);
        strip = bake(strip);
        // Its blur has the size of all blurs, so that the glass can mix them during a crossfade.
        Bitmap blurred = Bitmap.createScaledBitmap(strip, BLUR_W, blurHeight(), true);
        return new Backdrop(toHardware(strip), blurred, cards.get(0).item(), cards);
    }

    /**
     * Makes the backdrop for an app with no top shelf: its splash picture, else its logo large over a field of its
     * colour. The logo is the art of the icon, else the logo of the banner cut from its background. It uses the
     * resources of the highest density.
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
            return null; // the resources of the app failed: no backdrop
        }
        if (out == null) return null;
        out = bake(out);
        Bitmap blurred = blur(out); // before toHardware, which recycles the software copy
        return new Backdrop(toHardware(out), blurred, null);
    }

    private ActivityInfo launchActivity(String pkg) {
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        ResolveInfo ri = launch != null ? pm.resolveActivity(launch, 0) : null;
        return ri != null ? ri.activityInfo : null;
    }

    /**
     * Returns the logo of the icon without its background shape, over a field in the brand colour of the tile. The logo
     * is the foreground layer of an adaptive icon, or a legacy icon on transparency. Returns null if the icon has no
     * separate logo or has too few pixels. Then the banner is used.
     */
    private Bitmap iconScene(Resources res, ActivityInfo ai) {
        int iconId = ai.getIconResource();
        if (iconId == 0) return null;
        try {
            Drawable icon = res.getDrawableForDensity(iconId, DisplayMetrics.DENSITY_XXXHIGH, null);
            boolean adaptive = icon instanceof AdaptiveIconDrawable;
            Drawable fg = adaptive ? ((AdaptiveIconDrawable) icon).getForeground() : icon;
            // The logo of a legacy icon fills more of its canvas.
            Glyph glyph = fg != null ? Glyph.measure(fg, adaptive ? 0.5f : 0.6f) : null;
            if (glyph == null) return null;
            int bitmap = bitmapWidth(fg);
            float maxCanvas = bitmap > 0 ? bitmap * 1.5f : Float.MAX_VALUE; // enlarged not more than 1.5x
            int bannerId = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
            Drawable banner = bannerId != 0 ? res.getDrawable(bannerId, null) : null;
            int brand = brandColor(banner != null ? banner : icon);
            int[] gradient = gradientCorners(banner != null ? banner : adaptive ? ((AdaptiveIconDrawable) icon).getBackground() : null);
            return logoScene(fg, glyph, LOGO_SIZE, maxCanvas, 0.5f, brand, gradient, glyph.light < 0.25f); // a dark logo goes on a pale field
        } catch (RuntimeException e) {
            return null; // the resources of the app failed: the banner is used
        }
    }

    /**
     * Returns the banner (else the icon) with its background removed, over a field in the colour of that background.
     * Art that is a picture, not a logo on a background, shows as a card.
     */
    private Bitmap bannerScene(Resources res, ActivityInfo ai) {
        int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        if (id == 0) id = ai.getIconResource();
        if (id == 0) return null;
        Drawable art = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        if (art == null) return null;

        // Cuts at the pixel size of the app art. A vector is drawn at a size that needs no enlargement.
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        float aspect = iw > 0 && ih > 0 ? (float) iw / ih : 16f / 9f;
        int bitmap = bitmapWidth(art);
        int w = Math.max(64, Math.min(bitmap > 0 ? bitmap : 640, 720)); // sufficient for a LOGO_SIZE logo
        int h = Math.max(36, Math.round(w / aspect));
        Bitmap work = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        art.setBounds(0, 0, w, h);
        art.draw(new Canvas(work));
        int[] px = new int[w * h];
        work.getPixels(px, 0, w, 0, 0, w, h);
        float maxCanvas = w * (bitmap > 0 ? 1.5f : 1f); // not more than the sharpness of the icon

        int bg = keyBackground(px, w, h);
        Glyph glyph = bg != BUSY ? Glyph.of(px, w, h, 0.6f, false) : null;
        if (glyph != null) {
            work.setPixels(px, 0, w, 0, 0, w, h);
            boolean own = bg != NO_BACKGROUND;
            int brand = own ? bg : brandColor(art);
            boolean pale = own ? luminance(bg) > 0.6f : glyph.light < 0.25f;
            int[] gradient = own ? gradientCorners(art) : null;
            Bitmap out = logoScene(new BitmapDrawable(res, work), glyph, LOGO_SIZE, maxCanvas, 0f, brand, gradient, pale);
            work.recycle();
            return out;
        }

        // A picture: shows it as a card with round corners over its own colour.
        Bitmap card = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(work, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = h * 0.08f;
        new Canvas(card).drawRoundRect(0, 0, w, h, r, r, p);
        work.recycle();
        Glyph whole = new Glyph(new RectF(0, 0, 1, 1), aspect, 0.5f);
        Bitmap out = logoScene(new BitmapDrawable(res, card), whole, 300f, maxCanvas, 0f, brandColor(art), null, false);
        card.recycle();
        return out;
    }

    /**
     * The {@link #keyBackground} results that are not a colour: art with no background, and art with edges that are too
     * busy to be a background.
     */
    private static final int NO_BACKGROUND = 0, BUSY = 1;

    /**
     * Makes the background of the art transparent. The background comes from the edges of the art (flat, or a smooth
     * gradient between them). Each pixel becomes the most transparent colour that gives the original colour over that
     * background ("colour to alpha"). Returns the background colour at the centre of the art. Returns
     * {@link #NO_BACKGROUND} if the area around the logo is transparent. Returns {@link #BUSY} if the edges are too
     * busy to be a background.
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

        // First estimate: a Coons patch through the four edges. It is exact for flat and linear backgrounds.
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
        // Then refines the estimate for radial and uneven gradients. It uses the pixels that are near the background
        // colour, first with a large tolerance, then with a small tolerance.
        refine(px, bg, w, h, 40);
        refine(px, bg, w, h, 16);

        for (int i = 0; i < px.length; i++) px[i] = unblend(px[i], bg[i]);
        return bg[(h / 2) * w + w / 2];
    }

    /**
     * Calculates the background {@code bg} again as the mean of the pixels within {@code near} of it, on a coarse grid,
     * with smooth interpolation. Cells that the logo covers get the colour of their neighbours.
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
        while (valid < cells) { // fills the empty cells from their neighbours
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
     * Returns the most transparent colour that gives {@code p} over {@code b}. Differences at the level of the grain
     * and the compression noise of the background stay fully transparent.
     */
    private static int unblend(int p, int b) {
        int diff = difference(p, b);
        if (p >>> 24 == 0 || diff <= 16) return 0;
        float alpha = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float d = ch(p, shift) - ch(b, shift), room = d > 0 ? 255f - ch(b, shift) : ch(b, shift);
            alpha = Math.max(alpha, room > 0 ? Math.abs(d) / room : 1f);
        }
        alpha = Math.min(1f, alpha * Math.min(1f, (diff - 16) / 16f)); // fades in above the noise level
        if (alpha <= 0.02f) return 0;
        int out = Math.round(alpha * (p >>> 24)) << 24;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float f = ch(b, shift) + (ch(p, shift) - ch(b, shift)) / alpha;
            out |= Math.round(Math.max(0f, Math.min(255f, f))) << shift;
        }
        return out;
    }

    /** Returns the largest difference between two colours in one channel. */
    private static int difference(int a, int b) {
        return Math.max(Math.abs(ch(a, 16) - ch(b, 16)), Math.max(Math.abs(ch(a, 8) - ch(b, 8)), Math.abs(ch(a, 0) - ch(b, 0))));
    }

    /**
     * Returns one edge of the background of the art (side 0 top, 1 bottom, 2 left, 3 right), smoothed. For each pixel
     * along the edge, it takes the first opaque colour toward the centre, as medians over short runs. Returns null if
     * the art is almost transparent there, and an empty array if the edge varies too much.
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
        for (int r = 0; r < runs; r++) { // a run with no opaque pixels gets the colour of a neighbour
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
        // The mean difference from the smoothed edge for each channel. The edge of a photo is not smooth.
        return deviation / (3f * found) > 14f ? new int[0] : smooth;
    }

    private static int ch(int color, int shift) {
        return (color >> shift) & 255;
    }

    private static int mix(int a, int b) {
        return 0xFF000000 | ((ch(a, 16) + ch(b, 16)) / 2) << 16 | ((ch(a, 8) + ch(b, 8)) / 2) << 8 | (ch(a, 0) + ch(b, 0)) / 2;
    }

    /**
     * Returns a logo (transparent around it), large and centred above the dock with a soft shadow, over a field in the
     * brand colour. The size comes from the visible pixels, not the canvas: the area of a {@code size}-unit square for
     * any shape, in the space above the dock. The canvas is not more than {@code maxCanvas} pixels wide. Returns null
     * if the logo is then smaller than {@code minFill} of its correct size.
     */
    private Bitmap logoScene(Drawable fg, Glyph glyph, float size, float maxCanvas, float minFill, int brand, int[] gradient, boolean pale) {
        float s = width / 1920f, cx = width / 2f, cy = 370f * s;
        RectF g = glyph.bounds;
        float aspect = g.width() / g.height() * glyph.aspect; // the shape of the logo, in pixels
        float want = Math.min(size * s * (float) Math.sqrt(aspect), Math.min(700f * s, size * s * aspect));
        float canvasW = Math.min(want / g.width(), maxCanvas), canvasH = canvasW / glyph.aspect;
        float lw = g.width() * canvasW, lh = g.height() * canvasH;
        if (lw < minFill * want) return null;

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        // The field has only soft gradients, so it is drawn at a quarter of the size and enlarged.
        Bitmap field = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888);
        brandField(new Canvas(field), field.getWidth(), field.getHeight(), brand, gradient, pale, glyph.light, cx / 4f, cy / 4f, s / 4f);
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
     * The position and the brightness of a logo on its canvas.
     *
     * @param bounds The bounds of the visible pixels, as fractions of the canvas.
     * @param aspect The width of the canvas divided by its height.
     * @param light The mean luminance of the visible pixels, 0 to 1.
     */
    private record Glyph(RectF bounds, float aspect, float light) {
        private static final int N = 216; // the measurement size for icons

        /**
         * Returns the logo of an icon layer, or null if it is not a separate logo that covers not more than
         * {@code maxCover} of the canvas.
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
         * Returns the logo in w×h pixels, or null if there is none or it covers more than {@code maxCover} (a full
         * picture). With {@code noCards}, it also returns null for a large solid shape that fills its bounds (a logo
         * with its own background).
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
            // Ignores the few outermost visible pixels on each side.
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

    /** Returns the pixel width of the bitmap of a drawable, or 0 for a vector. */
    private static int bitmapWidth(Drawable d) {
        while (d instanceof DrawableWrapper) d = ((DrawableWrapper) d).getDrawable();
        return d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap().getWidth() : 0;
    }


    /**
     * Returns the brand colour: vivid pixels grouped by hue, each weighted by its saturation, and the mean of the
     * largest group. Art with too little colour gives grey.
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
            // Greys, black and white (saturation below 0.2, value below 0.18), calculated with integers. Most pixels do
            // not need the HSV call below.
            int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
            int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
            if (max < 46 || (max - min) * 5 < max) continue;
            Color.colorToHSV(p, hsv);
            int bin = (int) (hsv[0] / 360f * bins) % bins;
            float vivid = hsv[1] * hsv[2];
            weight[bin] += vivid;
            red[bin] += vivid * pr;
            green[bin] += vivid * pg;
            blue[bin] += vivid * pb;
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
     * Draws a field in the brand colour, brightest behind the logo. The field is deep for a light logo, pale for a dark
     * logo, and grey for a brand with no colour.
     */
    private static void brandField(Canvas c, int w, int h, int brand, int[] gradient, boolean pale, float logoLight,
                                   float cx, float cy, float s) {
        float[] hsv = new float[3];
        Color.colorToHSV(brand, hsv);
        float hue = hsv[0], sat = Math.min(hsv[1], 0.9f);
        // The glow stays below the brightness of the logo.
        float glow = pale ? 0.98f : sat < 0.12f ? 0.38f : 0.70f;
        while (!pale && glow > 0.30f && luminance(Color.HSVToColor(new float[]{hue, sat * 0.9f, glow})) > logoLight - 0.34f) {
            glow -= 0.02f;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setDither(true);
        if (gradient != null) {
            // The gradient of the tile across the screen: a 2x2 bitmap of its corner colours, stretched with a bilinear
            // filter. Then darker toward the dock, as the flat field is.
            Bitmap corners = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            for (int i = 0; i < 4; i++) corners.setPixel(i % 2, i / 2, backdropShade(gradient[i], pale));
            Paint stretch = new Paint(Paint.FILTER_BITMAP_FLAG);
            stretch.setDither(true);
            c.drawBitmap(corners, null, new RectF(-w / 2f, -h / 2f, w * 1.5f, h * 1.5f), stretch);
            corners.recycle();
            p.setShader(new LinearGradient(0, h * 0.35f, 0, h, 0, pale ? 0x33000000 : 0x99000000, Shader.TileMode.CLAMP));
            c.drawPaint(p);
        } else {
            int top = Color.HSVToColor(new float[]{hue, sat, pale ? 0.82f : 0.23f});
            int bottom = Color.HSVToColor(new float[]{hue, sat, pale ? 0.62f : 0.07f});
            p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
            c.drawPaint(p);
        }
        int bloom = Color.HSVToColor(new float[]{hue, sat * (pale ? 0.6f : 0.9f), glow}) & 0x00FFFFFF;
        int centre = gradient != null ? 0x99000000 : 0xE0000000; // softer over a gradient
        int[] falloff = {bloom | centre, bloom | 0x80000000, bloom | 0x26000000, bloom};
        p.setShader(new RadialGradient(cx, cy, 1050f * s, falloff, new float[]{0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawPaint(p);
    }

    /** Returns a tile colour for a backdrop: its hue and most of its saturation, darker behind a light logo. */
    private static int backdropShade(int color, boolean pale) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], 0.9f);
        hsv[2] = pale ? Math.max(0.6f, Math.min(hsv[2], 0.9f)) : Math.min(hsv[2] * 0.55f, 0.45f);
        return Color.HSVToColor(hsv);
    }

    /**
     * Returns the corner colours of art with a gradient background between clearly different colours, as {top-left,
     * top-right, bottom-left, bottom-right}. Returns null for a flat, transparent or busy background.
     */
    private static int[] gradientCorners(Drawable art) {
        if (art == null) return null;
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        int w = 96, h = Math.max(24, Math.min(96, Math.round(iw > 0 && ih > 0 ? w * (float) ih / iw : w * 9f / 16f)));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        art.setBounds(0, 0, w, h);
        art.draw(new Canvas(b));
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        int[] top = edge(px, w, h, 0), bottom = edge(px, w, h, 1), left = edge(px, w, h, 2), right = edge(px, w, h, 3);
        if (top == null || bottom == null || left == null || right == null
                || top.length == 0 || bottom.length == 0 || left.length == 0 || right.length == 0) return null;
        int[] corners = {mix(top[0], left[0]), mix(top[w - 1], right[0]), mix(bottom[0], left[h - 1]), mix(bottom[w - 1], right[h - 1])};
        int spread = 0;
        for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) spread = Math.max(spread, difference(corners[i], corners[j]));
        return spread >= 48 ? corners : null;
    }

    static float luminance(int p) {
        return (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
    }

    /** Copies a finished full-screen image to GPU memory on the IO thread and recycles the software copy. */
    private static Bitmap toHardware(Bitmap software) {
        Bitmap hardware = software.copy(Bitmap.Config.HARDWARE, false);
        if (hardware == null) return software;
        software.recycle();
        return hardware;
    }

    /**
     * Returns the launch screen of the app at screen size: the windowBackground of its launch theme. It must be a
     * picture of approximately 720p or more. Returns null for a flat colour or a small logo.
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
            return null; // the resources of the app failed: the logo is used
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
     * Returns true if the app {@code owner} can show this image: HTTPS only if the app has network access, and
     * content:// and android.resource:// only from its own package.
     */
    private boolean mayShow(String url, String owner) {
        if (owner == null) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if (scheme.equalsIgnoreCase("https")) {
            return pm.checkPermission(Manifest.permission.INTERNET, owner) == PackageManager.PERMISSION_GRANTED;
        }
        boolean local = scheme.equals(ContentResolver.SCHEME_CONTENT) || scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE);
        return local && ownedBy(uri, scheme, owner);
    }

    /**
     * Returns the cached copy of a recommendation image. If necessary, it downloads the image first: HTTPS (no
     * cleartext, no cookies, limited size) or the content of the app, as {@link #mayShow} allows.
     */
    private File fetch(String url, String owner) {
        // Checked before the cache, so that a different app with the same URI cannot get the cached image.
        if (!mayShow(url, owner)) return null;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        boolean https = scheme.equalsIgnoreCase("https");
        boolean local = scheme.equals(ContentResolver.SCHEME_CONTENT) || scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE);
        File out = cacheFile(url);
        if (out.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            out.setLastModified(System.currentTimeMillis()); // the time of use, for the cache trim
            return out;
        }
        HttpURLConnection http = null;
        File tmp = null;
        ScheduledFuture<?> deadline = null;
        try {
            InputStream in;
            if (https) {
                // The cast throws if the URL is not HTTPS.
                http = (HttpsURLConnection) new URL(url).openConnection();
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
                HttpURLConnection connection = http;
                deadline = DEADLINES.schedule(() -> { // the close stops a read that waits
                    try {
                        src.close();
                    } catch (IOException ignored) {
                        // already closed
                    }
                    if (connection != null) connection.disconnect();
                }, FETCH_DEADLINE_S, TimeUnit.SECONDS);
                //noinspection ResultOfMethodCallIgnored
                downloads.mkdirs();
                tmp = File.createTempFile("part", null, downloads);
                if (!copy(src, tmp)) return null;
            }
            return tmp.renameTo(out) ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (deadline != null) deadline.cancel(false);
            if (http != null) http.disconnect();
            if (tmp != null && tmp.exists()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    private boolean ownedBy(Uri uri, String scheme, String owner) {
        String authority = uri.getAuthority();
        // Refuses user info ("user@" and "%40"). ContentResolver reads it as a user id, and then the provider that it
        // reads is not the provider that this check found.
        if (authority == null || owner == null || uri.getUserInfo() != null || authority.indexOf('@') >= 0) return false;
        if (scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) return authority.equals(owner);
        ProviderInfo provider = pm.resolveContentProvider(authority, 0);
        return provider != null && provider.exported && owner.equals(provider.packageName);
    }

    /** Copies not more than MAX_DOWNLOAD bytes. Returns false if the source is larger. */
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

    /** Scales and centre-crops {@code src} to w×h. */
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

    /**
     * Draws the scrims into a screen-high software picture: darker toward the bottom for the dock, and at the top for
     * the clock. It draws in place if the picture is mutable, else on a copy. IO thread.
     */
    private Bitmap bake(Bitmap b) {
        if (!b.isMutable()) {
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, true);
            b.recycle();
            b = copy;
        }
        float h = b.getHeight();
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, h * SCRIM_BOTTOM_FROM, 0, h, 0x00000000, SCRIM_BOTTOM, Shader.TileMode.CLAMP));
        c.drawRect(0, h * SCRIM_BOTTOM_FROM, b.getWidth(), h, p);
        float top = SCRIM_TOP_DESIGN * h * width / (1920f * height); // design units to picture rows
        p.setShader(new LinearGradient(0, 0, 0, top, SCRIM_TOP, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, b.getWidth(), top, p);
        return b;
    }

    /**
     * Draws the details scrim of the full-screen shelf into the picture, behind the title, the description and Play. It
     * is darkest in the bottom-left corner.
     */
    private Bitmap bakeDetails(Bitmap b) {
        if (!b.isMutable()) {
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, true);
            b.recycle();
            b = copy;
        }
        float h = b.getHeight(), radius = DETAILS_SCRIM_RADIUS * b.getWidth() / 1920f;
        Paint p = new Paint();
        p.setShader(new RadialGradient(0f, h, radius, DETAILS_SCRIM, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, b.getWidth(), h, p);
        return b;
    }

    /** The details scrim: its colours and its radius in design units. HomeView uses the same values. */
    static final int[] DETAILS_SCRIM = {0xB8000000, 0x66000000, 0x00000000};
    static final float DETAILS_SCRIM_RADIUS = 1500f;

    /** Returns the height of each blur: the screen shape at {@link #BLUR_W} wide. */
    private int blurHeight() {
        return Math.max(1, Math.round(BLUR_W * (float) height / width));
    }

    static final int BLUR_W = 192;

    /** Returns a blurred copy, 192 px wide. A bilinear filter makes it smooth when it is drawn large. */
    static Bitmap blur(Bitmap src) {
        int w = BLUR_W;
        int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
        // Two downscale steps, so that the bilinear filter does not skip detail.
        Bitmap mid = Bitmap.createScaledBitmap(src, w * 3, h * 3, true);
        Bitmap small = Bitmap.createScaledBitmap(mid, w, h, true);
        if (mid != src && mid != small) mid.recycle();
        if (small == src) small = src.copy(Bitmap.Config.ARGB_8888, true);
        Bitmap out = boxBlur(small);
        if (small != src && out != small) small.recycle();
        return out;
    }

    /**
     * Applies three box blurs to {@code small}, which has the blur size. Returns {@code small} if it is mutable, else a
     * new mutable bitmap.
     */
    static Bitmap boxBlur(Bitmap small) {
        int w = small.getWidth(), h = small.getHeight();
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

    /**
     * Box-blurs each row of {@code src} (w×h) by {@link #BLUR_RADIUS} and writes the result transposed into {@code dst}
     * (h×w).
     */
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

    /** Returns the built-in wallpaper: soft blue, violet and teal light over near-black. */
    private static Bitmap aurora() {
        int w = 640, h = 360; // small: it is blurred and drawn full screen
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
