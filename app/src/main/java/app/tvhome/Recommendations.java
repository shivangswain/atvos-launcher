package app.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.HashMap;
import java.util.Map;

/**
 * What apps recommend on the TV home screen, read from the system TV provider: each app's
 * "Watch Next" entries and the programs in its preview channels. One landscape image per app is
 * kept, so the top shelf stays a still picture (preview videos are ignored).
 *
 * READ_TV_LISTINGS lets a launcher read every app's searchable programs; the provider forbids
 * selections, so rows are filtered here.
 */
final class Recommendations {

    /** One program to show behind the dock. */
    static final class Item {
        final String pkg, title, image, intentUri;

        Item(String pkg, String title, String image, String intentUri) {
            this.pkg = pkg;
            this.title = title;
            this.image = image;
            this.intentUri = intentUri;
        }
    }

    // Columns shared by preview and Watch Next programs, in this order.
    private static final int PKG = 0, TITLE = 1, THUMB = 2, THUMB_RATIO = 3, POSTER = 4, POSTER_RATIO = 5,
            INTENT = 6, ORDER_A = 7, ORDER_B = 8;

    private static final String[] WATCH_NEXT = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms._ID,
    };

    private static final String[] PREVIEW = {
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
    };

    private Recommendations() {}

    /** The best recommendation per package. IO thread. */
    static Map<String, Item> load(Context context) {
        Map<String, Item> best = new HashMap<>();
        ContentResolver cr = context.getContentResolver();

        // Watch Next first (what you're in the middle of): the most recently engaged entry wins.
        Map<String, Long> engaged = new HashMap<>();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Item item = item(c);
                if (item == null) continue;
                long when = c.isNull(ORDER_A) ? 0 : c.getLong(ORDER_A);
                Long prev = engaged.get(item.pkg);
                if (prev == null || when > prev) {
                    engaged.put(item.pkg, when);
                    best.put(item.pkg, item);
                }
            }
        } catch (RuntimeException ignored) {
            // No TV provider, no permission, or an unexpected schema: just no recommendations.
        }

        // Then the app's preview channels: first channel, heaviest program.
        Map<String, long[]> rank = new HashMap<>();
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = c.getString(PKG);
                if (pkg == null || engaged.containsKey(pkg)) continue;
                Item item = item(c);
                if (item == null) continue;
                long channel = c.isNull(ORDER_A) ? Long.MAX_VALUE : c.getLong(ORDER_A);
                long weight = c.isNull(ORDER_B) ? 0 : c.getLong(ORDER_B);
                long[] prev = rank.get(pkg);
                if (prev == null || channel < prev[0] || (channel == prev[0] && weight > prev[1])) {
                    rank.put(pkg, new long[]{channel, weight});
                    best.put(pkg, item);
                }
            }
        } catch (RuntimeException ignored) {
            // As above.
        }
        return best;
    }

    private static Item item(Cursor c) {
        String pkg = c.getString(PKG), title = c.getString(TITLE);
        String image = landscapeImage(c);
        if (pkg == null || image == null) return null;
        return new Item(pkg, title, image, c.getString(INTENT));
    }

    /** A wide image (16:9, 3:2 or 4:3), else one of unknown shape; portrait posters don't work as backdrops. */
    private static String landscapeImage(Cursor c) {
        String thumb = c.getString(THUMB), poster = c.getString(POSTER);
        int thumbRatio = c.isNull(THUMB_RATIO) ? -1 : c.getInt(THUMB_RATIO);
        int posterRatio = c.isNull(POSTER_RATIO) ? -1 : c.getInt(POSTER_RATIO);
        if (thumb != null && isLandscape(thumbRatio)) return thumb;
        if (poster != null && isLandscape(posterRatio)) return poster;
        if (thumb != null && thumbRatio == -1) return thumb;
        if (poster != null && posterRatio == -1) return poster;
        return null;
    }

    private static boolean isLandscape(int ratio) {
        return ratio == TvContract.PreviewPrograms.ASPECT_RATIO_16_9
                || ratio == TvContract.PreviewPrograms.ASPECT_RATIO_3_2
                || ratio == TvContract.PreviewPrograms.ASPECT_RATIO_4_3;
    }
}
