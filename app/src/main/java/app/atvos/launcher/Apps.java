package app.atvos.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.concurrent.atomic.AtomicInteger;

/** Launchable apps, their tile artwork, and the user's order, folders and hidden set. */
final class Apps {

    /** The home screen's tiles in display order (apps and folders), and hidden apps (shown greyed out while editing). */
    /**
     * The apps as loaded. {@code order} and {@code folders}: the saved layout with installs and
     * removals applied, if that changed it, for {@link #saveLoaded}; else null. {@code revision}:
     * see {@link #isLatest}.
     */
    record Layout(List<Entry> visible, List<Entry> hidden, String order, String folders, int revision) {
    }

    /** A folder of apps, as on tvOS. Its apps belong to the UI thread once loaded. */
    static final class Folder {
        final String id;
        final List<Entry> apps = new ArrayList<>();

        Folder(String id) {
            this.id = id;
        }
    }

    /**
     * One tile: an app, or a folder of them. Art fields are immutable; the label (a folder can be
     * renamed) and the animation fields belong to the UI thread.
     */
    static final class Entry {
        final String pkg;
        String label;
        final ComponentName component;
        final boolean leanback;
        final long updated;
        final Bitmap art;
        final BitmapShader shader;
        /** The folder this tile opens, or null for an app. */
        final Folder folder;
        /** An app the user can uninstall (not part of the system image). */
        final boolean removable;
        /** Its {@link ApplicationInfo#category}, to name a folder made from it. */
        final int category;

        float focus;
        float x = Float.NaN;
        float y;
        String shortLabel; // the label truncated to fit under its tile, worked out when first shown

        Entry(String pkg, String label, ComponentName component, boolean leanback, long updated, Bitmap art,
              boolean removable, int category) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.leanback = leanback;
            this.updated = updated;
            this.art = art;
            this.shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.folder = null;
            this.removable = removable;
            this.category = category;
        }

        /** A folder's tile; its key in the saved order is {@link #FOLDER} plus its id. */
        Entry(Folder folder, String name) {
            this.pkg = FOLDER + folder.id;
            this.label = name;
            this.component = null;
            this.leanback = false;
            this.updated = 0;
            this.art = null;
            this.shader = null;
            this.folder = folder;
            this.removable = false;
            this.category = ApplicationInfo.CATEGORY_UNDEFINED;
        }
    }

    /** Folder tiles' keys in the saved order: this, then the folder's id. */
    static final String FOLDER = "folder:";

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
    /** Folders as JSON: [{"id": "1", "name": "Movies & Video", "apps": ["com.netflix.ninja", …]}, …]. */
    private static final String KEY_FOLDERS = "folders";

    private final String self;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final int artW, artH;
    private final AtomicInteger revision = new AtomicInteger(); // bumped by every saveLayout(): the user's arranging

    Apps(Context context, int screenWidth) {
        self = context.getPackageName();
        pm = context.getPackageManager();
        prefs = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        // Art is rendered at the largest on-screen size (a focused tile).
        artW = Math.round(HomeView.TILE_W * HomeView.FOCUS_SCALE * screenWidth / HomeView.W);
        artH = Math.round(artW * HomeView.TILE_H / HomeView.TILE_W);
    }

    /**
     * Loads the apps in display order, with folders in their places. Reuses entries from
     * {@code previous} (every app and folder, inside folders too) when unchanged. Background thread.
     */
    Layout load(List<Entry> previous) {
        int seen = revision.get(); // before reading the saved layout
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
                ApplicationInfo app = ai.applicationInfo;
                Entry next = new Entry(ai.packageName, ri.loadLabel(pm).toString(), cn,
                        leanback.contains(ai.packageName), updated, renderArt(ri),
                        (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0, app.category);
                if (prev != null) { // an update: keep its place on screen and its focus animation
                    next.x = prev.x;
                    next.y = prev.y;
                    next.focus = prev.focus;
                }
                entries.put(ai.packageName, next);
            }
        }

        // Folders hold the apps still installed; one left empty goes. Each is a new object (the UI
        // thread owns the old ones), carrying over its tile's place on screen.
        Set<String> hiddenSet = hidden();
        Map<String, Entry> folders = new LinkedHashMap<>();
        Set<String> inFolders = new HashSet<>();
        String savedFolders = prefs.getString(KEY_FOLDERS, "[]");
        try {
            JSONArray list = new JSONArray(savedFolders);
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.getJSONObject(i);
                Folder folder = new Folder(f.getString("id"));
                JSONArray members = f.getJSONArray("apps");
                for (int j = 0; j < members.length(); j++) {
                    String p = members.getString(j);
                    Entry e = entries.get(p);
                    if (e != null && !hiddenSet.contains(p) && inFolders.add(p)) folder.apps.add(e);
                }
                if (folder.apps.isEmpty()) continue;
                Entry tile = new Entry(folder, f.optString("name", ""));
                Entry prev = old.get(tile.pkg);
                if (prev != null) {
                    tile.x = prev.x;
                    tile.y = prev.y;
                    tile.focus = prev.focus;
                }
                folders.put(tile.pkg, tile);
            }
        } catch (JSONException ignored) {
            // A damaged list: start without folders rather than without a home screen.
        }

        // Saved order first, then new apps.
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (String p : savedOrder()) {
            if (folders.containsKey(p) || (entries.containsKey(p) && !inFolders.contains(p))) order.add(p);
        }
        for (String p : folders.keySet()) order.add(p); // a folder missing from the order goes last
        List<Entry> fresh = new ArrayList<>();
        for (Entry e : entries.values()) if (!order.contains(e.pkg) && !inFolders.contains(e.pkg)) fresh.add(e);
        fresh.sort(Comparator.comparingInt(Apps::rank).thenComparing(e -> e.label.toLowerCase(Locale.ROOT)));
        for (Entry e : fresh) order.add(e.pkg);
        String joined = String.join(",", order), foldersJson = foldersJson(folders.values());
        boolean changed = !joined.equals(prefs.getString(KEY_ORDER, "")) || !foldersJson.equals(savedFolders);

        List<Entry> visible = new ArrayList<>(order.size()), hidden = new ArrayList<>();
        for (String p : order) {
            Entry folder = folders.get(p);
            if (folder != null) visible.add(folder);
            else (hiddenSet.contains(p) ? hidden : visible).add(entries.get(p));
        }
        return changed ? new Layout(visible, hidden, joined, foldersJson, seen) : new Layout(visible, hidden, null, null, seen);
    }

    /**
     * Saves what {@link #load} changed in the layout (installs and removals), once it's shown. Not
     * a new revision: a load reading the layout from before this works the same changes out. UI thread.
     */
    void saveLoaded(Layout layout) {
        if (layout.order() == null) return;
        prefs.edit().putString(KEY_ORDER, layout.order()).putString(KEY_FOLDERS, layout.folders()).apply();
    }

    /**
     * False if the home screen was arranged (and saved) while {@code layout} loaded: it was built
     * from the arrangement before, and showing or saving it would undo the user's move. UI thread.
     */
    boolean isLatest(Layout layout) {
        return layout.revision() == revision.get();
    }

    /** Persists the order, the folders and which apps are hidden, as arranged on the home screen. UI thread. */
    void saveLayout(List<Entry> visible, List<Entry> hidden) {
        revision.incrementAndGet();
        LinkedHashSet<String> order = new LinkedHashSet<>();
        Set<String> hiddenSet = new HashSet<>();
        List<Entry> folders = new ArrayList<>();
        for (Entry e : visible) {
            order.add(e.pkg);
            if (e.folder != null) folders.add(e);
        }
        for (Entry e : hidden) {
            order.add(e.pkg);
            hiddenSet.add(e.pkg);
        }
        prefs.edit().putString(KEY_ORDER, String.join(",", order)).putStringSet(KEY_HIDDEN, hiddenSet)
                .putString(KEY_FOLDERS, foldersJson(folders)).apply();
    }

    private static String foldersJson(Iterable<Entry> folders) {
        JSONArray list = new JSONArray();
        try {
            for (Entry tile : folders) {
                if (tile.folder.apps.isEmpty()) continue;
                JSONArray members = new JSONArray();
                for (Entry e : tile.folder.apps) members.put(e.pkg);
                list.put(new JSONObject().put("id", tile.folder.id).put("name", tile.label).put("apps", members));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e); // only for non-finite numbers, which this never puts
        }
        return list.toString();
    }

    /**
     * A new folder holding {@code first}, named after its kind of app the way Android names it
     * ("Movies & Video"), else "Folder". Its id is unused by any folder in {@code tiles}. UI thread.
     */
    static Entry newFolder(Context context, Entry first, List<Entry> tiles) {
        int next = 1;
        for (Entry t : tiles) {
            if (t.folder == null) continue;
            try {
                next = Math.max(next, Integer.parseInt(t.folder.id) + 1);
            } catch (NumberFormatException ignored) {
                // not one of ours
            }
        }
        CharSequence kind = first.category != ApplicationInfo.CATEGORY_UNDEFINED
                ? ApplicationInfo.getCategoryTitle(context, first.category) : null;
        Folder folder = new Folder(Integer.toString(next));
        folder.apps.add(first);
        return new Entry(folder, kind != null ? kind.toString() : context.getString(R.string.folder));
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

    @SuppressWarnings("deprecation") // the int-flag overloads work on every version from Android 12
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
    private static int averageColor(Drawable d) {
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
