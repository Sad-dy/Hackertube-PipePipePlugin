package org.hackertube.pipepipe.bridge;

import android.content.Context;
import android.database.Cursor;

import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Optional reflection bridge into the PipePipe Android host ({@code App.getApp()} and
 * {@code NewPipeDatabase}) so the plugin can store offline snapshots under PipePipe's files dir
 * and read Watch History / Local Playlists for personalization.
 *
 * <p>All lookups fail safely outside PipePipe Android (e.g., JVM unit tests).</p>
 */
public final class PipePipeBridge {

    public static final int MAX_SEEN_IDS = 200;

    public static final class PersonalizationSignals {
        public final List<String> likes;
        public final List<String> dislikes;
        public final List<String> seen;

        public PersonalizationSignals(final List<String> likes,
                                      final List<String> dislikes,
                                      final List<String> seen) {
            this.likes = Collections.unmodifiableList(likes);
            this.dislikes = Collections.unmodifiableList(dislikes);
            this.seen = Collections.unmodifiableList(seen);
        }

        public static PersonalizationSignals empty() {
            return new PersonalizationSignals(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }
    }

    private PipePipeBridge() {
    }

    @Nullable
    public static Context getAppContext() {
        try {
            final Class<?> appClass = Class.forName("org.schabi.newpipe.App");
            final Method getApp = appClass.getMethod("getApp");
            final Object app = getApp.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (final Throwable ignored) {
            // Outside Android/PipePipe host
        }
        return null;
    }

    public static File getStorageDir() {
        final Context ctx = getAppContext();
        final File base;
        if (ctx != null && ctx.getFilesDir() != null) {
            base = new File(ctx.getFilesDir(), "hackertube");
        } else {
            base = new File(System.getProperty("java.io.tmpdir", "/tmp"), "hackertube");
        }
        if (!base.exists()) {
            //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
        }
        return base;
    }

    public static PersonalizationSignals readSignals(final boolean includePersonalization,
                                                     final List<String> likesPlaylists,
                                                     final List<String> dislikesPlaylists) {
        if (!includePersonalization) {
            return PersonalizationSignals.empty();
        }
        final Context ctx = getAppContext();
        if (ctx == null) {
            return PersonalizationSignals.empty();
        }
        try {
            final Object supportDb = openReadableRoomDb(ctx);
            if (supportDb == null) {
                return PersonalizationSignals.empty();
            }
            final Set<String> seenSet = queryHistoryVideoIds(supportDb);
            final Set<String> dislikeSet = queryPlaylistVideoIds(supportDb, dislikesPlaylists);
            final Set<String> rawLikes = queryPlaylistVideoIds(supportDb, likesPlaylists);

            // Dislikes win if a video sits in both playlists.
            final List<String> likes = new ArrayList<>(rawLikes.size());
            for (final String vid : rawLikes) {
                if (!dislikeSet.contains(vid)) {
                    likes.add(vid);
                }
            }
            return new PersonalizationSignals(
                    likes,
                    new ArrayList<>(dislikeSet),
                    new ArrayList<>(seenSet));
        } catch (final Throwable ignored) {
            return PersonalizationSignals.empty();
        }
    }

    @Nullable
    private static Object openReadableRoomDb(final Context ctx) {
        try {
            final Class<?> dbClass = Class.forName("org.schabi.newpipe.NewPipeDatabase");
            final Method getInstance = dbClass.getMethod("getInstance", Context.class);
            final Object roomDb = getInstance.invoke(null, ctx);
            if (roomDb == null) {
                return null;
            }
            final Object openHelper = roomDb.getClass().getMethod("getOpenHelper").invoke(roomDb);
            if (openHelper == null) {
                return null;
            }
            return openHelper.getClass().getMethod("getReadableDatabase").invoke(openHelper);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static Set<String> queryHistoryVideoIds(final Object supportDb) {
        final String sql = "SELECT s.url FROM stream_history h "
                + "JOIN streams s ON h.stream_id = s.uid "
                + "GROUP BY s.uid ORDER BY MAX(h.access_date) DESC LIMIT " + MAX_SEEN_IDS;
        return queryVideoIds(supportDb, sql, null, MAX_SEEN_IDS);
    }

    private static Set<String> queryPlaylistVideoIds(final Object supportDb,
                                                     final List<String> playlistNames) {
        if (playlistNames == null || playlistNames.isEmpty()) {
            return Collections.emptySet();
        }
        final List<String> normalized = new ArrayList<>(playlistNames.size());
        final StringBuilder placeholders = new StringBuilder();
        for (final String name : playlistNames) {
            final String clean = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (clean.isEmpty()) {
                continue;
            }
            if (!normalized.isEmpty()) {
                placeholders.append(", ");
            }
            placeholders.append('?');
            normalized.add(clean);
        }
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        final String sql = "SELECT s.url FROM playlist_stream_join j "
                + "JOIN playlists p ON j.playlist_id = p.uid "
                + "JOIN streams s ON j.stream_id = s.uid "
                + "WHERE LOWER(TRIM(p.name)) IN (" + placeholders + ") "
                + "ORDER BY j.join_index ASC";
        return queryVideoIds(supportDb, sql, normalized.toArray(new Object[0]), 200);
    }

    private static Set<String> queryVideoIds(final Object supportDb,
                                             final String sql,
                                             @Nullable final Object[] bindArgs,
                                             final int maxCount) {
        final Set<String> out = new LinkedHashSet<>();
        Cursor cursor = null;
        try {
            if (bindArgs == null) {
                final Method query = supportDb.getClass().getMethod("query", String.class);
                cursor = (Cursor) query.invoke(supportDb, sql);
            } else {
                final Method query = supportDb.getClass()
                        .getMethod("query", String.class, Object[].class);
                cursor = (Cursor) query.invoke(supportDb, sql, bindArgs);
            }
            if (cursor == null) {
                return out;
            }
            while (cursor.moveToNext() && out.size() < maxCount) {
                final String url = cursor.getString(0);
                final String vid = HackerTubeUrlUtil.extractYouTubeId(url);
                if (vid != null) {
                    out.add(vid);
                }
            }
        } catch (final Throwable ignored) {
            // Best-effort bridge; ignore missing table/column if schema changes in future app versions
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (final Throwable ignored) {
                }
            }
        }
        return out;
    }
}
