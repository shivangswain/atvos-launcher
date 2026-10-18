package app.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Launchable apps, their tile artwork, and the user's order / hidden set. */
final class Apps {

    /** Visible apps in display order, and hidden apps (shown greyed out while editing). */
    static final class Layout {
        final List<Entry> visible, hidden;

        Layout(List<Entry> visible, List<Entry> hidden) {
            this.visible = visible;
            this.hidden = hidden;
        }
    }

    /** One app tile. Art fields are immutable; animation fields belong to the UI thread. */
    static final class Entry {
        final String pkg;
        final String label;
        final ComponentName component;
        final boolean leanback;
        final long updated;
        final Bitmap art;
        final BitmapShader shader;

        float focus;
        float x = Float.NaN;
        float y;

        Entry(String pkg, String label, ComponentName component, boolean leanback, long updated, Bitmap art) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.leanback = leanback;
            this.updated = updated;
            this.art = art;
            this.shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    // First-run order; everything else follows alphabetically, Settings last.
    private static final List<String> PREFERRED = Arrays.asList(
            "com.apple.atve.androidtv.appletv",
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar",
            "com.crunchyroll.crunchyroid",
            "com.disney.disneyplus",
            "com.spotify.tv.android",
            "com.plexapp.android");
    private static final String SETTINGS = "com.android.tv.settings";

    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";

    private final String self;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final int artW, artH;

    Apps(Context context, int screenWidth) {
        self = context.getPackageName();
        pm = context.getPackageManager();
        prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        // Art is rendered at the largest on-screen size (a focused tile).
        artW = Math.round(HomeView.TILE_W * HomeView.FOCUS_SCALE * screenWidth / HomeView.W);
        artH = Math.round(artW * HomeView.TILE_H / HomeView.TILE_W);
    }

    /** Loads the apps in display order. Reuses entries from {@code previous} when unchanged. Background thread. */
    Layout load(List<Entry> previous) {
        Map<String, Entry> old = new HashMap<>();
        for (Entry e : previous) old.put(e.pkg, e);

        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        Set<String> leanback = new HashSet<>();
        for (ResolveInfo ri : query(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            if (found.putIfAbsent(ri.activityInfo.packageName, ri) == null) leanback.add(ri.activityInfo.packageName);
        }
        for (ResolveInfo ri : query(Intent.CATEGORY_LAUNCHER)) found.putIfAbsent(ri.activityInfo.packageName, ri);
        found.remove(self);

        Map<String, Entry> entries = new HashMap<>();
        for (ResolveInfo ri : found.values()) {
            ActivityInfo ai = ri.activityInfo;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            long updated = lastUpdate(ai.packageName);
            Entry prev = old.get(ai.packageName);
            if (prev != null && prev.updated == updated && prev.component.equals(cn)) {
                entries.put(prev.pkg, prev);
            } else {
                Entry next = new Entry(ai.packageName, ri.loadLabel(pm).toString(), cn,
                        leanback.contains(ai.packageName), updated, renderArt(ri));
                if (prev != null) { // an update: keep its place on screen and its focus animation
                    next.x = prev.x;
                    next.y = prev.y;
                    next.focus = prev.focus;
                }
                entries.put(ai.packageName, next);
            }
        }

        // Saved order first, then new apps.
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (String p : savedOrder()) if (entries.containsKey(p)) order.add(p);
        List<Entry> fresh = new ArrayList<>();
        for (Entry e : entries.values()) if (!order.contains(e.pkg)) fresh.add(e);
        fresh.sort(Comparator.comparingInt(Apps::rank).thenComparing(e -> e.label.toLowerCase(Locale.ROOT)));
        for (Entry e : fresh) order.add(e.pkg);
        String joined = String.join(",", order);
        if (!joined.equals(prefs.getString(KEY_ORDER, ""))) prefs.edit().putString(KEY_ORDER, joined).apply();

        Set<String> hiddenSet = hidden();
        List<Entry> visible = new ArrayList<>(order.size()), hidden = new ArrayList<>();
        for (String p : order) (hiddenSet.contains(p) ? hidden : visible).add(entries.get(p));
        return new Layout(visible, hidden);
    }

    /** Persists the order and which apps are hidden, as arranged in edit mode. */
    void saveLayout(List<Entry> visible, List<Entry> hidden) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        Set<String> hiddenSet = new HashSet<>();
        for (Entry e : visible) order.add(e.pkg);
        for (Entry e : hidden) {
            order.add(e.pkg);
            hiddenSet.add(e.pkg);
        }
        prefs.edit().putString(KEY_ORDER, String.join(",", order)).putStringSet(KEY_HIDDEN, hiddenSet).apply();
    }

    void hide(String pkg) {
        Set<String> hidden = new HashSet<>(hidden());
        hidden.add(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
    }

    private Set<String> hidden() {
        return prefs.getStringSet(KEY_HIDDEN, Collections.emptySet());
    }

    private List<String> savedOrder() {
        String s = prefs.getString(KEY_ORDER, "");
        return s.isEmpty() ? Collections.emptyList() : Arrays.asList(s.split(","));
    }

    private static int rank(Entry e) {
        if (SETTINGS.equals(e.pkg)) return Integer.MAX_VALUE;
        int i = PREFERRED.indexOf(e.pkg);
        return i >= 0 ? i : PREFERRED.size();
    }

    @SuppressWarnings("deprecation") // int-flag overloads are fine down to minSdk
    private List<ResolveInfo> query(String category) {
        return pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
    }

    @SuppressWarnings("deprecation")
    private long lastUpdate(String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The app's TV banner, cropped to the tile; or its icon on a tile tinted with the icon's colour. */
    private Bitmap renderArt(ResolveInfo ri) {
        Bitmap out = Bitmap.createBitmap(artW, artH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Drawable banner = null;
        try {
            banner = ri.activityInfo.loadBanner(pm);
        } catch (RuntimeException ignored) {
            // Broken resources in another app shouldn't take the home screen down.
        }
        if (banner != null) {
            int iw = banner.getIntrinsicWidth(), ih = banner.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) {
                banner.setBounds(0, 0, artW, artH);
            } else {
                float s = Math.max((float) artW / iw, (float) artH / ih);
                int w = Math.round(iw * s), h = Math.round(ih * s);
                banner.setBounds((artW - w) / 2, (artH - h) / 2, (artW + w) / 2, (artH + h) / 2);
            }
            banner.draw(c);
            return out;
        }

        Drawable icon = ri.loadIcon(pm);
        int color = averageColor(icon);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(hsv[2], 0.55f);
        int base = Color.HSVToColor(hsv);
        hsv[2] *= 0.6f;
        Paint p = new Paint();
        p.setDither(true);
        p.setShader(new LinearGradient(0, 0, 0, artH, base, Color.HSVToColor(hsv), Shader.TileMode.CLAMP));
        c.drawPaint(p);
        int size = Math.round(artH * 0.56f);
        icon.setBounds((artW - size) / 2, (artH - size) / 2, (artW + size) / 2, (artH + size) / 2);
        icon.draw(c);
        return out;
    }

    /** The alpha-weighted average colour of a drawable. */
    static int averageColor(Drawable d) {
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 16, 16);
        d.draw(new Canvas(b));
        int[] px = new int[256];
        b.getPixels(px, 0, 16, 0, 0, 16, 16);
        b.recycle();
        long r = 0, g = 0, bl = 0, w = 0;
        for (int p : px) {
            int a = p >>> 24;
            r += (long) ((p >> 16) & 255) * a;
            g += (long) ((p >> 8) & 255) * a;
            bl += (long) (p & 255) * a;
            w += a;
        }
        if (w == 0) return 0xFF3A3A3C;
        return Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w));
    }
}
