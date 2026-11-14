package org.hackertube.pipepipe.bridge;

import android.content.Context;

import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.linkhandler.HackerTubeUrlUtil;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * Optional reflection bridge into the PipePipe Android host ({@code App.getApp()} and
 * {@code NewPipeDatabase}) so the plugin can store offline snapshots under PipePipe's files dir,
 * read Watch History / Local Playlists for personalization, and seed default Likes/Dislikes
 * starter playlists once on first launch.
 *
 * <p>All lookups fail safely outside PipePipe Android (e.g., JVM unit tests).</p>
 */
public final class PipePipeBridge {

    public static final int MAX_SEEN_IDS = 200;
    public static final String SEED_LIKES_VIDEO_ID = "dQw4w9WgXcQ";
    public static final String SEED_DISLIKES_VIDEO_ID = "XqZsoesa55w";

    private static final Set<String> SEED_PLACEHOLDER_IDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(SEED_LIKES_VIDEO_ID, SEED_DISLIKES_VIDEO_ID)));

    private static final List<SeedPlaylistSpec> STARTER_PLAYLISTS = Collections.unmodifiableList(
            Arrays.asList(
                    new SeedPlaylistSpec(
                            HackerTubePrefs.DEFAULT_LIKES_PLAYLIST,
                            SEED_LIKES_VIDEO_ID,
                            "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)",
                            "Rick Astley",
                            214L),
                    new SeedPlaylistSpec(
                            HackerTubePrefs.DEFAULT_DISLIKES_PLAYLIST,
                            SEED_DISLIKES_VIDEO_ID,
                            "Baby Shark Dance | #babyshark Most Viewed Video | Animal Songs | PINKFONG Songs for Children",
                            "Baby Shark - Pinkfong Kids\u2019 Songs & Stories",
                            137L)
            ));

    private static final AtomicBoolean SEED_DISPATCHED = new AtomicBoolean(false);

    private static final class SeedPlaylistSpec {
        final String playlistName;
        final String vid;
        final String title;
        final String uploader;
        final long durationSeconds;

        SeedPlaylistSpec(final String playlistName,
                         final String vid,
                         final String title,
                         final String uploader,
                         final long durationSeconds) {
            this.playlistName = playlistName;
            this.vid = vid;
            this.title = title;
            this.uploader = uploader;
            this.durationSeconds = durationSeconds;
        }
    }

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
            final Object roomDb = openRoomDb(ctx);
            final Object supportDb = openDatabaseFromRoom(roomDb, false);
            if (supportDb == null) {
                return PersonalizationSignals.empty();
            }
            final Set<String> seenSet = queryHistoryVideoIds(supportDb);
            final Set<String> rawDislikes = queryPlaylistVideoIds(supportDb, dislikesPlaylists);
            final Set<String> rawLikes = queryPlaylistVideoIds(supportDb, likesPlaylists);
            return buildSignals(seenSet, rawLikes, rawDislikes);
        } catch (final Throwable ignored) {
            return PersonalizationSignals.empty();
        }
    }

    /**
     * Filters out starter placeholder IDs so default non-empty Likes/Dislikes playlists do not
     * bias recommendations before real user signals are added, while preserving dislike-wins order.
     */
    public static PersonalizationSignals buildSignals(@Nullable final Set<String> seenSet,
                                                      @Nullable final Set<String> rawLikes,
                                                      @Nullable final Set<String> rawDislikes) {
        final Set<String> activeDislikes = filterIdsExcluding(rawDislikes, SEED_PLACEHOLDER_IDS, null);
        final Set<String> activeLikes = filterIdsExcluding(rawLikes, SEED_PLACEHOLDER_IDS, activeDislikes);
        final List<String> seen = seenSet == null
                ? Collections.emptyList()
                : new ArrayList<>(seenSet);
        return new PersonalizationSignals(
                new ArrayList<>(activeLikes),
                new ArrayList<>(activeDislikes),
                seen);
    }

    /**
     * Seeds default non-empty {@code HackerTube Likes} and {@code HackerTube Dislikes} playlists
     * once on first launch in a daemon background thread so Room never runs on the main thread.
     */
    public static void ensureStarterPlaylistsSeededAsync(final int serviceId,
                                                         final HackerTubePrefs prefs) {
        if (prefs.isStarterPlaylistsSeeded() || getAppContext() == null) {
            return;
        }
        if (!SEED_DISPATCHED.compareAndSet(false, true)) {
            return;
        }
        final String baseUrl = prefs.getBaseUrl();
        final boolean useVideoFrames = prefs.isUseVideoFrames();
        final Thread t = new Thread(() -> {
            try {
                if (seedStarterPlaylistsIfMissing(serviceId, baseUrl, useVideoFrames)) {
                    prefs.markStarterPlaylistsSeeded();
                } else {
                    SEED_DISPATCHED.set(false);
                }
            } catch (final Throwable ignored) {
                SEED_DISPATCHED.set(false);
            }
        }, "HackerTube-StarterPlaylists");
        t.setDaemon(true);
        t.start();
    }

    private static boolean seedStarterPlaylistsIfMissing(final int serviceId,
                                                         final String baseUrl,
                                                         final boolean useVideoFrames) {
        final Context ctx = getAppContext();
        if (ctx == null) {
            return false;
        }
        return seedStarterPlaylistsInRoomDb(openRoomDb(ctx), serviceId, baseUrl, useVideoFrames);
    }

    /**
     * Inserts missing default {@code HackerTube Likes} and {@code HackerTube Dislikes} playlists
     * inside a single Room database transaction and refreshes Room observers if any were created.
     */
    public static boolean seedStarterPlaylistsInRoomDb(@Nullable final Object roomDb,
                                                       final int serviceId,
                                                       final String baseUrl,
                                                       final boolean useVideoFrames) {
        final Object writableDb = openDatabaseFromRoom(roomDb, true);
        if (writableDb == null) {
            return false;
        }
        boolean createdAny = false;
        try {
            invokePublic(writableDb, "beginTransaction");
            try {
                for (final SeedPlaylistSpec spec : STARTER_PLAYLISTS) {
                    if (seedSinglePlaylistIfMissing(
                            writableDb, serviceId, baseUrl, useVideoFrames, spec)) {
                        createdAny = true;
                    }
                }
                invokePublic(writableDb, "setTransactionSuccessful");
            } finally {
                invokeNoArgQuietly(writableDb, "endTransaction");
            }
        } catch (final Throwable ignored) {
            return false;
        }
        if (createdAny) {
            refreshInvalidationTracker(roomDb);
        }
        return true;
    }

    private static boolean seedSinglePlaylistIfMissing(final Object writableDb,
                                                       final int serviceId,
                                                       final String baseUrl,
                                                       final boolean useVideoFrames,
                                                       final SeedPlaylistSpec spec) throws Exception {
        final String normalizedName = spec.playlistName.trim().toLowerCase(Locale.ROOT);
        final Long existingPlaylistUid = queryFirstLong(
                writableDb,
                "SELECT uid FROM playlists WHERE LOWER(TRIM(name)) = ? LIMIT 1",
                new Object[]{normalizedName});
        if (existingPlaylistUid != null) {
            return false;
        }
        final String streamUrl = HackerTubeUrlUtil.canonicalWatchUrl(baseUrl, spec.vid);
        final String thumbUrl = HackerTubeUrlUtil.buildThumbnailUrl(spec.vid, useVideoFrames);
        Long streamUid = queryFirstLong(
                writableDb,
                "SELECT uid FROM streams WHERE service_id = ? AND url = ? LIMIT 1",
                new Object[]{serviceId, streamUrl});
        if (streamUid == null) {
            execSql(writableDb,
                    "INSERT OR IGNORE INTO streams "
                            + "(service_id, url, title, stream_type, duration, uploader, thumbnail_url, is_paid) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                    new Object[]{serviceId, streamUrl, spec.title, "VIDEO_STREAM",
                            spec.durationSeconds, spec.uploader, thumbUrl});
            streamUid = queryFirstLong(
                    writableDb,
                    "SELECT uid FROM streams WHERE service_id = ? AND url = ? LIMIT 1",
                    new Object[]{serviceId, streamUrl});
            if (streamUid == null) {
                return false;
            }
        }
        execSql(writableDb,
                "INSERT INTO playlists (name, thumbnail_url, display_index) VALUES (?, ?, -1)",
                new Object[]{spec.playlistName, thumbUrl});
        final Long playlistUid = queryFirstLong(
                writableDb,
                "SELECT uid FROM playlists WHERE LOWER(TRIM(name)) = ? ORDER BY uid DESC LIMIT 1",
                new Object[]{normalizedName});
        if (playlistUid == null) {
            return false;
        }
        execSql(writableDb,
                "INSERT INTO playlist_stream_join (playlist_id, stream_id, join_index) VALUES (?, ?, 0)",
                new Object[]{playlistUid, streamUid});
        return true;
    }

    private static Set<String> filterIdsExcluding(@Nullable final Set<String> source,
                                                    @Nullable final Set<String> excludedPrimary,
                                                    @Nullable final Set<String> excludedSecondary) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> out = new LinkedHashSet<>(source.size());
        for (final String vid : source) {
            if (vid == null || vid.isEmpty()) {
                continue;
            }
            if (excludedPrimary != null && excludedPrimary.contains(vid)) {
                continue;
            }
            if (excludedSecondary != null && excludedSecondary.contains(vid)) {
                continue;
            }
            out.add(vid);
        }
        return out;
    }

    @Nullable
    private static Object openRoomDb(final Context ctx) {
        try {
            final Class<?> dbClass = Class.forName("org.schabi.newpipe.NewPipeDatabase");
            final Method getInstance = dbClass.getMethod("getInstance", Context.class);
            return getInstance.invoke(null, ctx);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object openDatabaseFromRoom(@Nullable final Object roomDb, final boolean writable) {
        if (roomDb == null) {
            return null;
        }
        try {
            final Object openHelper = invokePublic(roomDb, "getOpenHelper");
            if (openHelper == null) {
                return null;
            }
            final String methodName = writable ? "getWritableDatabase" : "getReadableDatabase";
            return invokePublic(openHelper, methodName);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static void execSql(final Object supportDb, final String sql, final Object[] bindArgs)
            throws Exception {
        final Method exec = findPublicMethod(supportDb.getClass(), "execSQL",
                String.class, Object[].class);
        if (exec == null) {
            throw new NoSuchMethodException("execSQL");
        }
        exec.invoke(supportDb, sql, bindArgs);
    }

    @Nullable
    private static Long queryFirstLong(final Object supportDb,
                                       final String sql,
                                       final Object[] bindArgs) {
        Object cursor = null;
        try {
            cursor = openCursor(supportDb, sql, bindArgs);
            if (cursor != null && Boolean.TRUE.equals(invokePublic(cursor, "moveToFirst"))) {
                final Method getLong = findPublicMethod(cursor.getClass(), "getLong", int.class);
                if (getLong != null) {
                    final Object val = getLong.invoke(cursor, 0);
                    return val instanceof Number ? ((Number) val).longValue() : null;
                }
            }
        } catch (final Throwable ignored) {
            // Fall through to null
        } finally {
            closeQuietly(cursor);
        }
        return null;
    }

    private static void refreshInvalidationTracker(@Nullable final Object roomDb) {
        if (roomDb == null) {
            return;
        }
        try {
            final Object tracker = invokePublic(roomDb, "getInvalidationTracker");
            if (tracker != null) {
                invokePublic(tracker, "refreshVersionsAsync");
            }
        } catch (final Throwable ignored) {
            // Non-fatal: active observers will refresh on next query anyway
        }
    }

    private static void invokeNoArgQuietly(@Nullable final Object target, final String methodName) {
        if (target == null) {
            return;
        }
        try {
            invokePublic(target, methodName);
        } catch (final Throwable ignored) {
        }
    }

    private static void closeQuietly(@Nullable final Object cursor) {
        invokeNoArgQuietly(cursor, "close");
    }

    @Nullable
    private static Object openCursor(final Object supportDb,
                                     final String sql,
                                     @Nullable final Object[] bindArgs) throws Exception {
        if (bindArgs == null) {
            final Method query = findPublicMethod(supportDb.getClass(), "query", String.class);
            if (query == null) {
                throw new NoSuchMethodException("query(String)");
            }
            return query.invoke(supportDb, sql);
        }
        final Method query = findPublicMethod(
                supportDb.getClass(), "query", String.class, Object[].class);
        if (query == null) {
            throw new NoSuchMethodException("query(String, Object[])");
        }
        return query.invoke(supportDb, sql, bindArgs);
    }

    @Nullable
    private static Object invokePublic(final Object target, final String methodName) throws Exception {
        final Method m = findPublicMethod(target.getClass(), methodName);
        if (m == null) {
            throw new NoSuchMethodException(methodName);
        }
        return m.invoke(target);
    }

    @Nullable
    private static Method findPublicMethod(final Class<?> cls,
                                           final String name,
                                           final Class<?>... paramTypes) {
        for (Class<?> cur = cls; cur != null; cur = cur.getSuperclass()) {
            final Method ifaceMethod = findOnPublicInterfaces(cur.getInterfaces(), name, paramTypes);
            if (ifaceMethod != null) {
                return ifaceMethod;
            }
            if (Modifier.isPublic(cur.getModifiers())) {
                try {
                    final Method m = cur.getMethod(name, paramTypes);
                    if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
                        return m;
                    }
                } catch (final NoSuchMethodException ignored) {
                }
            }
        }
        try {
            return cls.getMethod(name, paramTypes);
        } catch (final NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findOnPublicInterfaces(final Class<?>[] interfaces,
                                                 final String name,
                                                 final Class<?>... paramTypes) {
        for (final Class<?> iface : interfaces) {
            if (Modifier.isPublic(iface.getModifiers())) {
                try {
                    return iface.getMethod(name, paramTypes);
                } catch (final NoSuchMethodException ignored) {
                }
            }
            final Method parentMatch = findOnPublicInterfaces(
                    iface.getInterfaces(), name, paramTypes);
            if (parentMatch != null) {
                return parentMatch;
            }
        }
        return null;
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
        Object cursor = null;
        try {
            cursor = openCursor(supportDb, sql, bindArgs);
            if (cursor == null) {
                return out;
            }
            final Method moveToNext = findPublicMethod(cursor.getClass(), "moveToNext");
            final Method getString = findPublicMethod(cursor.getClass(), "getString", int.class);
            if (moveToNext == null || getString == null) {
                return out;
            }
            while (Boolean.TRUE.equals(moveToNext.invoke(cursor)) && out.size() < maxCount) {
                final Object raw = getString.invoke(cursor, 0);
                final String url = raw instanceof String ? (String) raw : null;
                final String vid = HackerTubeUrlUtil.extractYouTubeId(url);
                if (vid != null) {
                    out.add(vid);
                }
            }
        } catch (final Throwable ignored) {
            // Best-effort bridge; ignore missing table/column if schema changes in future app versions
        } finally {
            closeQuietly(cursor);
        }
        return out;
    }
}
