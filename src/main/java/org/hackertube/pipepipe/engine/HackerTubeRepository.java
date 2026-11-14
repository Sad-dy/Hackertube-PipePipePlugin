package org.hackertube.pipepipe.engine;

import org.hackertube.pipepipe.api.HackerTubeApiClient;
import org.hackertube.pipepipe.api.HackerTubeApiClient.ListParams;
import org.hackertube.pipepipe.api.SnapshotSyncManager;
import org.hackertube.pipepipe.bridge.PipePipeBridge;
import org.hackertube.pipepipe.bridge.PipePipeBridge.PersonalizationSignals;
import org.hackertube.pipepipe.config.HackerTubeAreaCatalog;
import org.hackertube.pipepipe.config.HackerTubePrefs;
import org.hackertube.pipepipe.model.HackerTubeModels.AreaEntry;
import org.hackertube.pipepipe.model.HackerTubeModels.CategoryRef;
import org.hackertube.pipepipe.model.HackerTubeModels.MentionRecord;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.PlaylistSummary;
import org.hackertube.pipepipe.model.HackerTubeModels.SearchResponse;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoDetail;
import org.hackertube.pipepipe.model.HackerTubeModels.VideoRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Online-first data facade with seamless automatic fallback to {@link LocalSnapshotStore},
 * {@link LocalRecommender}, and {@link LocalQueryEngine}.
 */
public final class HackerTubeRepository {

    public static final int DEFAULT_PAGE_SIZE = 36;
    private static final long MEMORY_CATALOG_TTL_MS = 15L * 60L * 1000L;

    private final HackerTubePrefs prefs;
    private final HackerTubeApiClient apiClient;
    private final LocalSnapshotStore store;
    private final SnapshotSyncManager syncManager;

    @Nullable private volatile List<AreaEntry> cachedAreas;
    private volatile long cachedAreasAtMs;
    @Nullable private volatile List<PlaylistSummary> cachedPlaylists;
    private volatile long cachedPlaylistsAtMs;

    public HackerTubeRepository(final HackerTubePrefs prefs) {
        this(prefs, new HackerTubeApiClient(), new LocalSnapshotStore(PipePipeBridge.getStorageDir()));
    }

    public HackerTubeRepository(final HackerTubePrefs prefs,
                                final HackerTubeApiClient apiClient,
                                final LocalSnapshotStore store) {
        this.prefs = prefs;
        this.apiClient = apiClient;
        this.store = store;
        this.syncManager = new SnapshotSyncManager(apiClient, store);
    }

    public HackerTubePrefs getPrefs() {
        return prefs;
    }

    public List<VideoRecord> getFeed(final String seed, final int offset, final int limit)
            throws IOException {
        syncManager.maybeTriggerBackgroundSync(prefs);
        final PersonalizationSignals sig = readPersonalization();
        final List<String> seenForHome = prefs.isHideWatchedInHome()
                ? sig.seen : Collections.emptyList();

        if (!prefs.isOfflineFallbackMode()) {
            try {
                final List<VideoRecord> raw = apiClient.fetchFeed(
                        prefs.getBaseUrl(), sig.likes, sig.dislikes, seenForHome,
                        seed, offset, Math.min(limit + 12, 60));
                store.recordObservedYtMetadata(raw);
                return filterHomeOnline(raw, limit);
            } catch (final Exception onlineErr) {
                return fallbackFeedOrThrow(sig, seenForHome, seed, offset, limit, onlineErr);
            }
        }
        return fallbackFeedOrThrow(sig, seenForHome, seed, offset, limit, null);
    }

    public List<VideoRecord> getList(final ListParams params,
                                     final boolean isKioskOrBrowse,
                                     final int offset,
                                     final int limit) throws IOException {
        syncManager.maybeTriggerBackgroundSync(prefs);
        final boolean hideNews = isKioskOrBrowse && prefs.isHideNewsVideos();
        final boolean hideZeroRec = false;

        if (!prefs.isOfflineFallbackMode()) {
            try {
                final int fetchSize = LocalQueryEngine.needsClientSidePostFilter(params, hideNews, hideZeroRec)
                        ? Math.min(100, Math.max(limit * 2, 60))
                        : limit;
                final List<VideoRecord> raw = apiClient.fetchList(
                        prefs.getBaseUrl(), params, offset, fetchSize);
                store.recordObservedYtMetadata(raw);
                if (!LocalQueryEngine.needsClientSidePostFilter(params, hideNews, hideZeroRec)) {
                    return raw;
                }
                final List<VideoRecord> filtered = LocalQueryEngine.applyFilters(
                        raw, params, hideNews, hideZeroRec);
                return filtered.size() > limit ? filtered.subList(0, limit) : filtered;
            } catch (final Exception onlineErr) {
                return fallbackListOrThrow(params, hideNews, hideZeroRec, offset, limit, onlineErr);
            }
        }
        return fallbackListOrThrow(params, hideNews, hideZeroRec, offset, limit, null);
    }

    public SearchResponse search(final String query,
                                 @Nullable final ListParams params,
                                 final int offset,
                                 final int limit) throws IOException {
        syncManager.maybeTriggerBackgroundSync(prefs);
        if (query != null && "*".equals(query.trim())) {
            final List<VideoRecord> list = getList(
                    params == null ? new ListParams() : params, false, offset, limit);
            return new SearchResponse(false, list);
        }

        if (!prefs.isOfflineFallbackMode()) {
            try {
                final SearchResponse resp = apiClient.fetchSearch(
                        prefs.getBaseUrl(), query, params, offset, limit);
                store.recordObservedYtMetadata(resp.results);
                if (!LocalQueryEngine.needsClientSidePostFilter(params, false, false)) {
                    return resp;
                }
                final List<VideoRecord> filtered = LocalQueryEngine.applyFilters(
                        resp.results, params, false, false);
                return new SearchResponse(resp.weak, filtered);
            } catch (final Exception onlineErr) {
                return fallbackSearchOrThrow(query, params, offset, limit, onlineErr);
            }
        }
        return fallbackSearchOrThrow(query, params, offset, limit, null);
    }

    public List<AreaEntry> getAreas() {
        syncManager.maybeTriggerBackgroundSync(prefs);
        final long now = System.currentTimeMillis();
        final List<AreaEntry> mem = cachedAreas;
        if (mem != null && !mem.isEmpty() && (now - cachedAreasAtMs) < MEMORY_CATALOG_TTL_MS) {
            return mem;
        }
        if (!prefs.isOfflineFallbackMode()) {
            try {
                final List<AreaEntry> live = apiClient.fetchBrowseAreas(prefs.getBaseUrl());
                if (!live.isEmpty()) {
                    cachedAreas = live;
                    cachedAreasAtMs = now;
                    store.saveBrowseAreas(live);
                    return live;
                }
            } catch (final Exception ignored) {
            }
        }
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap != null && !snap.areas.isEmpty()) {
            cachedAreas = snap.areas;
            cachedAreasAtMs = now;
            return snap.areas;
        }
        return HackerTubeAreaCatalog.DEFAULT_AREAS;
    }

    public List<AreaEntry> getCachedAreasOnly() {
        final List<AreaEntry> mem = cachedAreas;
        if (mem != null && !mem.isEmpty()) {
            return mem;
        }
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap != null && !snap.areas.isEmpty()) {
            return snap.areas;
        }
        return HackerTubeAreaCatalog.DEFAULT_AREAS;
    }

    @Nullable
    public AreaEntry getArea(final String areaId) {
        for (final AreaEntry area : getAreas()) {
            if (area.id.equalsIgnoreCase(areaId)) {
                return area;
            }
        }
        return HackerTubeAreaCatalog.getDefault(areaId);
    }

    public List<PlaylistSummary> getCachedPlaylistsOnly() {
        final List<PlaylistSummary> mem = cachedPlaylists;
        if (mem != null && !mem.isEmpty()) {
            return mem;
        }
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap != null && !snap.playlistSummaries.isEmpty()) {
            return snap.playlistSummaries;
        }
        return Collections.emptyList();
    }

    public List<String> getCachedChannelsMatching(final String query, final int limit) {
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap == null || snap.videos.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        final String lower = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        final boolean all = lower.isEmpty() || "*".equals(lower);
        final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (final VideoRecord v : snap.videos) {
            if (v.channel.isEmpty()) {
                continue;
            }
            if (all || v.channel.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                counts.put(v.channel, counts.getOrDefault(v.channel, 0) + 1);
            }
        }
        final List<String> names = new ArrayList<>(counts.keySet());
        names.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        return names.size() > limit ? new ArrayList<>(names.subList(0, limit)) : names;
    }

    public List<PlaylistSummary> getPlaylists() throws IOException {
        syncManager.maybeTriggerBackgroundSync(prefs);
        final long now = System.currentTimeMillis();
        final List<PlaylistSummary> mem = cachedPlaylists;
        if (mem != null && !mem.isEmpty() && (now - cachedPlaylistsAtMs) < MEMORY_CATALOG_TTL_MS) {
            return mem;
        }
        if (!prefs.isOfflineFallbackMode()) {
            try {
                final List<PlaylistSummary> live = apiClient.fetchPlaylists(prefs.getBaseUrl());
                if (!live.isEmpty()) {
                    cachedPlaylists = live;
                    cachedPlaylistsAtMs = now;
                    store.savePlaylists(live, null);
                    return live;
                }
            } catch (final Exception onlineErr) {
                final LocalSnapshotData snap = store.getSnapshot();
                if (snap != null && !snap.playlistSummaries.isEmpty()) {
                    cachedPlaylists = snap.playlistSummaries;
                    cachedPlaylistsAtMs = now;
                    return snap.playlistSummaries;
                }
                throw asIoException("Failed to load HackerTube playlists", onlineErr);
            }
        }
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap != null && !snap.playlistSummaries.isEmpty()) {
            cachedPlaylists = snap.playlistSummaries;
            cachedPlaylistsAtMs = now;
            return snap.playlistSummaries;
        }
        throw new IOException("No cached curated playlists available offline yet.");
    }

    public PlaylistDetail getCuratedPlaylist(final String slug) throws IOException {
        syncManager.maybeTriggerBackgroundSync(prefs);
        if (!prefs.isOfflineFallbackMode()) {
            try {
                final PlaylistDetail detail = apiClient.fetchPlaylistDetail(prefs.getBaseUrl(), slug);
                store.recordObservedYtMetadata(detail.videos);
                store.savePlaylistDetail(detail);
                return detail;
            } catch (final Exception onlineErr) {
                final LocalSnapshotData snap = store.getSnapshot();
                if (snap != null && snap.playlistsBySlug.containsKey(slug)) {
                    return snap.playlistsBySlug.get(slug);
                }
                throw asIoException("Failed to load playlist " + slug, onlineErr);
            }
        }
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap != null && snap.playlistsBySlug.containsKey(slug)) {
            return snap.playlistsBySlug.get(slug);
        }
        throw new IOException("Playlist \"" + slug + "\" is not cached in the local snapshot.");
    }

    public CategoryPlaylistResult getCategoryPlaylist(final String catId, final int offset, final int limit)
            throws IOException {
        CategoryRef meta = null;
        for (final AreaEntry a : getAreas()) {
            for (final CategoryRef c : a.categories) {
                if (c.id.equalsIgnoreCase(catId)) {
                    meta = c;
                    break;
                }
            }
            if (meta != null) {
                break;
            }
        }
        final ListParams params = new ListParams();
        params.cat = catId;
        final List<VideoRecord> videos = getList(params, false, offset, limit);
        return new CategoryPlaylistResult(meta, videos);
    }

    @Nullable
    public VideoDetail getVideoDetail(final String vid) throws IOException {
        if (!prefs.isOfflineFallbackMode()) {
            try {
                final VideoDetail detail = apiClient.fetchVideoDetail(prefs.getBaseUrl(), vid);
                if (detail.video != null) {
                    store.recordObservedYtMetadata(Collections.singletonList(detail.video));
                }
                return detail;
            } catch (final Exception onlineErr) {
                final VideoDetail local = findLocalVideoDetail(vid);
                if (local != null) {
                    return local;
                }
                throw asIoException("Failed to load HackerTube metadata for " + vid, onlineErr);
            }
        }
        return findLocalVideoDetail(vid);
    }

    public List<VideoRecord> getRelated(final String vid, final int limit) {
        if (!prefs.isOfflineFallbackMode()) {
            try {
                final List<VideoRecord> live = apiClient.fetchRelated(prefs.getBaseUrl(), vid, limit);
                store.recordObservedYtMetadata(live);
                return live;
            } catch (final Exception ignored) {
            }
        }
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap != null) {
            return LocalRecommender.findRelatedLocal(snap, vid, limit);
        }
        return Collections.emptyList();
    }

    private List<VideoRecord> filterHomeOnline(final List<VideoRecord> raw, final int limit) {
        final boolean hideNews = prefs.isHideNewsVideos();
        final boolean hideZeroRec = prefs.isHideZeroRecHome();
        final List<VideoRecord> out = new ArrayList<>(Math.min(limit, raw.size()));
        final Set<String> seenTitles = new HashSet<>();
        for (final VideoRecord v : raw) {
            if (hideNews && "news".equalsIgnoreCase(v.shelfLife)) {
                continue;
            }
            if (hideZeroRec && v.hn.recommenders <= 0) {
                continue;
            }
            final String norm = LocalRecommender.normalizeTitle(v.title);
            if (!norm.isEmpty() && !seenTitles.add(norm)) {
                continue;
            }
            out.add(v);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private List<VideoRecord> fallbackFeedOrThrow(final PersonalizationSignals sig,
                                                  final List<String> seenForHome,
                                                  final String seed,
                                                  final int offset,
                                                  final int limit,
                                                  @Nullable final Exception cause) throws IOException {
        final LocalSnapshotData snap = requireSnapshotOrThrow(cause);
        return LocalRecommender.recommendFeed(
                snap, sig.likes, sig.dislikes, seenForHome, seed,
                prefs.isHideWatchedInHome(), prefs.isHideNewsVideos(), prefs.isHideZeroRecHome(),
                offset, limit);
    }

    private List<VideoRecord> fallbackListOrThrow(final ListParams params,
                                                  final boolean hideNews,
                                                  final boolean hideZeroRec,
                                                  final int offset,
                                                  final int limit,
                                                  @Nullable final Exception cause) throws IOException {
        final LocalSnapshotData snap = requireSnapshotOrThrow(cause);
        return LocalQueryEngine.queryList(snap, params, hideNews, hideZeroRec, offset, limit);
    }

    private SearchResponse fallbackSearchOrThrow(final String query,
                                                 @Nullable final ListParams params,
                                                 final int offset,
                                                 final int limit,
                                                 @Nullable final Exception cause) throws IOException {
        final LocalSnapshotData snap = requireSnapshotOrThrow(cause);
        return LocalQueryEngine.search(snap, query, params, offset, limit);
    }

    @Nullable
    private VideoDetail findLocalVideoDetail(final String vid) {
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap == null) {
            return null;
        }
        final VideoRecord v = snap.videosByVid.get(vid);
        final List<MentionRecord> comments = snap.mentionsByVid.getOrDefault(vid, Collections.emptyList());
        if (v == null && comments.isEmpty()) {
            return null;
        }
        return new VideoDetail(v, comments);
    }

    private LocalSnapshotData requireSnapshotOrThrow(@Nullable final Exception cause) throws IOException {
        final LocalSnapshotData snap = store.getSnapshot();
        if (snap != null && snap.isReady()) {
            return snap;
        }
        if (cause != null) {
            throw asIoException("HackerTube is unreachable and no offline snapshot is cached yet", cause);
        }
        throw new IOException("Local snapshot mode is enabled, but hackertube-data.zip has not been cached yet.");
    }

    private PersonalizationSignals readPersonalization() {
        return PipePipeBridge.readSignals(
                prefs.isPersonalizeFromPipePipe(),
                prefs.getLikesPlaylistNames(),
                prefs.getDislikesPlaylistNames());
    }

    private static IOException asIoException(final String msg, final Exception cause) {
        if (cause instanceof IOException) {
            return new IOException(msg + ": " + cause.getMessage(), cause);
        }
        return new IOException(msg, cause);
    }

    public static final class CategoryPlaylistResult {
        @Nullable public final CategoryRef category;
        public final List<VideoRecord> videos;

        public CategoryPlaylistResult(@Nullable final CategoryRef category,
                                      final List<VideoRecord> videos) {
            this.category = category;
            this.videos = videos;
        }
    }
}
