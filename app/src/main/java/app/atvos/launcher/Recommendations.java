package app.atvos.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the recommendations of apps from the TV provider: the Watch Next programs and the preview channel programs of
 * each app. It keeps the first landscape images of each app for the pages of the full-screen top shelf. Preview videos
 * are ignored.
 * <p>
 * READ_TV_LISTINGS gives access to the searchable programs of all apps. The provider does not accept selections, so
 * this class filters the rows.
 */
final class Recommendations {

    /** The two sources. They are also the bits of {@link Backdrops#sources}. */
    static final int WATCH_NEXT = 1, PREVIEWS = 2;

    /** The maximum number of programs for each app. */
    static final int MAX_PAGES = 8;

    /** The result: the programs of each app in page order, and the apps that publish each source. */
    static final class Found {
        final Map<String, List<Item>> pages = new HashMap<>();
        final Set<String> watchNext = new HashSet<>(), previews = new HashSet<>();
    }

    /** A program and its rank in the programs of its app: lower {@code a} first, then lower {@code b}. */
    private record Ranked(Item item, long a, long b) {
    }

    /** Returns {@code v} as a rank that puts larger values first. The negation does not overflow. */
    private static long descending(long v) {
        return v == Long.MIN_VALUE ? Long.MAX_VALUE : -v;
    }

    /**
     * One program for the backdrop or for the row of its app. {@code section}: the row, null for Watch Next ("Continue
     * Watching"), "" for a preview program ("Recommended"). {@code details}: a line such as "S2, E4 · The Long Night"
     * or "Movie · Drama · 2025 · 2 hr 3 min", or null. {@code progress}: the watched fraction, 0 to 1 (0 if not known).
     * {@code remainingMillis}: the time that remains (0 if not known).
     */
    record Item(String pkg, String title, String image, String intentUri, String section, String description,
                String details, float progress, long remainingMillis) {
    }

    // Columns of the preview and Watch Next programs, in this order.
    private static final int PKG = 0, TITLE = 1, THUMB = 2, THUMB_RATIO = 3, POSTER = 4, POSTER_RATIO = 5,
            INTENT = 6, ORDER_A = 7, ORDER_B = 8, BROWSABLE = 9, SHORT_DESCRIPTION = 10, LONG_DESCRIPTION = 11,
            TYPE = 12, GENRES = 13, SEASON = 14, EPISODE = 15, EPISODE_TITLE = 16, DURATION = 17, RELEASED = 18,
            POSITION = 19;

    private static final String[] WATCH_NEXT_COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_TYPE,
            TvContract.WatchNextPrograms.COLUMN_CANONICAL_GENRE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_RELEASE_DATE,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
    };

    private static final String[] PREVIEW_COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_TYPE,
            TvContract.PreviewPrograms.COLUMN_CANONICAL_GENRE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_RELEASE_DATE,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
    };

    private Recommendations() {}

    /**
     * Returns the programs of each package in rank order, without the sources that are off for the app. Watch Next
     * programs come first, the last engaged first. Preview programs follow, by channel and then by weight. IO thread.
     */
    static Found load(Context context, Set<String> noWatchNext, Set<String> noPreviews) {
        Found found = new Found();
        ContentResolver cr = context.getContentResolver();
        Map<String, List<Ranked>> engaged = new HashMap<>(), channels = new HashMap<>();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                try { // a bad row skips only that row
                    if (dismissed(c)) continue;
                    String pkg = c.getString(PKG);
                    if (pkg == null || cardImage(c) == null) continue;
                    found.watchNext.add(pkg);
                    if (noWatchNext.contains(pkg)) continue;
                    Item item = item(context, c, null);
                    if (item == null) continue;
                    long when = c.isNull(ORDER_A) ? 0 : c.getLong(ORDER_A), id = c.isNull(ORDER_B) ? 0 : c.getLong(ORDER_B);
                    engaged.computeIfAbsent(pkg, k -> new ArrayList<>()).add(new Ranked(item, descending(when), descending(id))); // newest first
                } catch (RuntimeException ignored) {
                    // skips the row
                }
            }
        } catch (RuntimeException ignored) {
            // No TV provider, no permission or an unknown schema: no recommendations.
        }
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                try { // a bad row skips only that row
                    if (dismissed(c)) continue;
                    String pkg = c.getString(PKG);
                    if (pkg == null || cardImage(c) == null) continue;
                    found.previews.add(pkg);
                    if (noPreviews.contains(pkg)) continue;
                    long channel = c.isNull(ORDER_A) ? Long.MAX_VALUE : c.getLong(ORDER_A);
                    Item item = item(context, c, ""); // the section is "Recommended" for all channels
                    if (item == null) continue;
                    long weight = c.isNull(ORDER_B) ? 0 : c.getLong(ORDER_B);
                    channels.computeIfAbsent(pkg, k -> new ArrayList<>()).add(new Ranked(item, channel, descending(weight)));
                } catch (RuntimeException ignored) {
                    // skips the row
                }
            }
        } catch (RuntimeException ignored) {
            // No TV provider, no permission or an unknown schema: no recommendations.
        }
        Comparator<Ranked> order = Comparator.comparingLong(Ranked::a).thenComparingLong(Ranked::b);
        Set<String> apps = new HashSet<>(engaged.keySet());
        apps.addAll(channels.keySet());
        for (String pkg : apps) {
            List<Item> pages = new ArrayList<>(MAX_PAGES);
            Set<String> images = new HashSet<>();
            for (Map<String, List<Ranked>> source : List.of(engaged, channels)) {
                List<Ranked> ranked = source.get(pkg);
                if (ranked == null) continue;
                ranked.sort(order);
                for (Ranked r : ranked) {
                    if (pages.size() < MAX_PAGES && images.add(r.item().image())) pages.add(r.item()); // one page for each image
                }
            }
            found.pages.put(pkg, pages);
        }
        return found;
    }

    /** Returns true if the user removed the program. The system clears its browsable flag. */
    private static boolean dismissed(Cursor c) {
        return !c.isNull(BROWSABLE) && c.getInt(BROWSABLE) == 0;
    }

    private static Item item(Context context, Cursor c, String section) {
        String pkg = c.getString(PKG), title = c.getString(TITLE);
        String image = cardImage(c);
        if (pkg == null || image == null) return null;
        String description = text(c, SHORT_DESCRIPTION);
        if (description == null) description = text(c, LONG_DESCRIPTION);
        long duration = c.isNull(DURATION) ? 0 : c.getLong(DURATION), position = c.isNull(POSITION) ? 0 : c.getLong(POSITION);
        float progress = duration > 0 && position > 0 ? Math.min(1f, (float) position / duration) : 0f;
        long remaining = progress > 0f ? Math.max(0, duration - position) : 0;
        return new Item(pkg, title, image, c.getString(INTENT), section, description, details(context, c, duration), progress,
                remaining);
    }

    private static String text(Cursor c, int column) {
        String s = c.isNull(column) ? null : c.getString(column);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /**
     * Returns "S2, E4 · The Long Night" for an episode, else "Movie · Drama · 2025 · 2 hr 3 min", with the known parts
     * only.
     */
    private static String details(Context context, Cursor c, long durationMillis) {
        List<String> parts = new ArrayList<>();
        String season = text(c, SEASON), episode = text(c, EPISODE), episodeTitle = text(c, EPISODE_TITLE);
        if (episode != null) {
            parts.add(season != null ? context.getString(R.string.season_episode, season, episode)
                    : context.getString(R.string.episode_number, episode));
            if (episodeTitle != null) parts.add(episodeTitle);
        } else {
            int type = c.isNull(TYPE) ? -1 : c.getInt(TYPE);
            if (type == TvContract.PreviewPrograms.TYPE_MOVIE) parts.add(context.getString(R.string.type_movie));
            else if (type == TvContract.PreviewPrograms.TYPE_TV_SERIES) parts.add(context.getString(R.string.type_tv_show));
            String genres = text(c, GENRES);
            if (genres != null) {
                int added = 0;
                for (String g : TvContract.Programs.Genres.decode(genres)) {
                    if (added == 2 || TvContract.Programs.Genres.MOVIES.equals(g)) continue;
                    String name = genreName(g);
                    if (name.isEmpty()) continue;
                    parts.add(name);
                    added++;
                }
            }
            String released = text(c, RELEASED);
            if (released != null && released.length() >= 4 && released.substring(0, 4).chars().allMatch(Character::isDigit)) {
                parts.add(released.substring(0, 4));
            }
            long minutes = durationMillis / 60_000;
            if (minutes > 0) {
                parts.add(minutes >= 60 ? (minutes % 60 == 0 ? context.getString(R.string.duration_h, minutes / 60)
                        : context.getString(R.string.duration_hm, minutes / 60, minutes % 60))
                        : context.getString(R.string.duration_m, minutes));
            }
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** Returns a canonical genre ("TECH_SCIENCE") as words ("Tech & Science"). */
    private static String genreName(String g) {
        StringBuilder out = new StringBuilder();
        for (String word : g.split("_")) {
            if (word.isEmpty()) continue; // the text can contain empty words, for example "_DRAMA"
            if (out.length() > 0) out.append(g.startsWith("TECH") || g.startsWith("ARTS") || g.startsWith("ANIMAL")
                    || g.startsWith("FAMILY") || g.startsWith("LIFE") ? " & " : " ");
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * Returns a wide image (16:9, 3:2 or 4:3), else a square image, else an image of unknown shape. Portrait posters
     * are not used.
     */
    private static String cardImage(Cursor c) {
        String thumb = c.getString(THUMB), poster = c.getString(POSTER);
        // An empty string is the same as no value.
        if (thumb != null && thumb.isEmpty()) thumb = null;
        if (poster != null && poster.isEmpty()) poster = null;
        int thumbRatio = c.isNull(THUMB_RATIO) ? -1 : c.getInt(THUMB_RATIO);
        int posterRatio = c.isNull(POSTER_RATIO) ? -1 : c.getInt(POSTER_RATIO);
        if (thumb != null && isLandscape(thumbRatio)) return thumb;
        if (poster != null && isLandscape(posterRatio)) return poster;
        if (thumb != null && thumbRatio == TvContract.PreviewPrograms.ASPECT_RATIO_1_1) return thumb;
        if (poster != null && posterRatio == TvContract.PreviewPrograms.ASPECT_RATIO_1_1) return poster;
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
