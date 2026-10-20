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
 * What apps recommend on the TV home screen, read from the system TV provider: each app's
 * "Watch Next" entries and the programs in its preview channels. A few landscape images per app
 * are kept, the best first: the full-screen top shelf pages through them, as tvOS's carousel does.
 * They stay still pictures (preview videos are ignored).
 * <p>
 * READ_TV_LISTINGS lets a launcher read every app's searchable programs; the provider forbids
 * selections, so rows are filtered here.
 */
final class Recommendations {

    /** The two sources, also the bits of {@link Backdrops#sources}. */
    static final int WATCH_NEXT = 1, PREVIEWS = 2;

    /** Programs kept per app, for the full-screen top shelf to page through and the row to show. */
    static final int MAX_PAGES = 8;

    /**
     * What was found: the programs to page through per app (the one to show first), and which apps
     * publish each source at all.
     */
    static final class Found {
        final Map<String, List<Item>> pages = new HashMap<>();
        final Set<String> watchNext = new HashSet<>(), previews = new HashSet<>();
    }

    /** A program and where it ranks among its app's: lower {@code a}, then lower {@code b}, first. */
    private record Ranked(Item item, long a, long b) {
    }

    /**
     * One program to show behind the dock or in its app's row. {@code section}: the row it's in,
     * null for Watch Next ("Continue Watching"), "" for a preview program ("Recommended").
     * {@code details}: a line like "S2, E4 · The Long Night" or "Movie · Drama · 2025 · 2 hr 3 min",
     * or null. {@code progress}: how much has been watched, 0–1 (0 when unknown), and
     * {@code remainingMillis} how long is left (0 when unknown).
     */
    record Item(String pkg, String title, String image, String intentUri, String section, String description,
                String details, float progress, long remainingMillis) {
    }

    // Columns shared by preview and Watch Next programs, in this order.
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
     * Each package's programs, best first, leaving out the sources switched off for each app: its
     * Watch Next entries, most recently engaged first (what you're in the middle of), then its
     * preview programs, first channel and heaviest first. IO thread.
     */
    static Found load(Context context, Set<String> noWatchNext, Set<String> noPreviews) {
        Found found = new Found();
        ContentResolver cr = context.getContentResolver();
        Map<String, List<Ranked>> engaged = new HashMap<>(), channels = new HashMap<>();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                try { // one app's odd row mustn't cost every other app theirs
                    if (dismissed(c)) continue;
                    String pkg = c.getString(PKG);
                    if (pkg == null || cardImage(c) == null) continue;
                    found.watchNext.add(pkg);
                    if (noWatchNext.contains(pkg)) continue;
                    Item item = item(context, c, null);
                    if (item == null) continue;
                    long when = c.isNull(ORDER_A) ? 0 : c.getLong(ORDER_A), id = c.isNull(ORDER_B) ? 0 : c.getLong(ORDER_B);
                    engaged.computeIfAbsent(pkg, k -> new ArrayList<>()).add(new Ranked(item, -when, -id)); // newest first
                } catch (RuntimeException ignored) {
                    // skip it
                }
            }
        } catch (RuntimeException ignored) {
            // No TV provider, no permission, or an unexpected schema: just no recommendations.
        }
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                try { // as above
                    if (dismissed(c)) continue;
                    String pkg = c.getString(PKG);
                    if (pkg == null || cardImage(c) == null) continue;
                    found.previews.add(pkg);
                    if (noPreviews.contains(pkg)) continue;
                    long channel = c.isNull(ORDER_A) ? Long.MAX_VALUE : c.getLong(ORDER_A);
                    Item item = item(context, c, ""); // "Recommended", whatever the app names its channels
                    if (item == null) continue;
                    long weight = c.isNull(ORDER_B) ? 0 : c.getLong(ORDER_B);
                    channels.computeIfAbsent(pkg, k -> new ArrayList<>()).add(new Ranked(item, channel, -weight));
                } catch (RuntimeException ignored) {
                    // skip it
                }
            }
        } catch (RuntimeException ignored) {
            // As above.
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
                    if (pages.size() < MAX_PAGES && images.add(r.item().image())) pages.add(r.item()); // one page per picture
                }
            }
            found.pages.put(pkg, pages);
        }
        return found;
    }

    /**
     * Removed by the user (from Watch Next, or from a channel, in the TV's own home screen): the
     * system clears the program's browsable flag and the row can stay on long after.
     */
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

    /** "S2, E4 · The Long Night" for an episode; else "Movie · Drama · 2025 · 2 hr 3 min", as much as is known. */
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

    /** A canonical genre ("TECH_SCIENCE") as words ("Tech & Science"). */
    private static String genreName(String g) {
        StringBuilder out = new StringBuilder();
        for (String word : g.split("_")) {
            if (word.isEmpty()) continue; // any app can write any text here: "_DRAMA", "SCI__FI", ""
            if (out.length() > 0) out.append(g.startsWith("TECH") || g.startsWith("ARTS") || g.startsWith("ANIMAL")
                    || g.startsWith("FAMILY") || g.startsWith("LIFE") ? " & " : " ");
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * A wide image (16:9, 3:2 or 4:3), else a square one (music and podcasts: a square card in the
     * row), else one of unknown shape; portrait posters work as neither backdrop nor card.
     */
    private static String cardImage(Cursor c) {
        String thumb = c.getString(THUMB), poster = c.getString(POSTER);
        // Some apps write "" rather than leaving a column empty.
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
